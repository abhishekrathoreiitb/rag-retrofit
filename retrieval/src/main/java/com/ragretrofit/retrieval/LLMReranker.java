package com.ragretrofit.retrieval;

import com.ragretrofit.indexer.model.CodeChunk;
import com.ragretrofit.stores.vector.VectorStore;
import dev.langchain4j.model.chat.ChatLanguageModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM-based reranker for code retrieval results.
 * Uses cloud LLM to provide context-aware ranking of code chunks.
 */
public class LLMReranker {
    
    private static final Logger logger = LoggerFactory.getLogger(LLMReranker.class);
    
    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper;
    private final String rerankPrompt;
    private final String patternRerankPrompt;
    
    // Configuration
    private static final int MAX_CHUNKS_PER_REQUEST = 20;
    private static final int MAX_CONTENT_LENGTH = 500;
    
    public LLMReranker(ChatLanguageModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "Chat model cannot be null");
        this.objectMapper = new ObjectMapper();
        
        // Load prompt templates
        this.rerankPrompt = loadPromptTemplate("rerank_code_relevance.prompt");
        this.patternRerankPrompt = loadPromptTemplate("rerank_pattern_match.prompt");
        
        logger.info("Initialized LLM reranker");
    }
    
    /**
     * Rerank search results based on query relevance
     */
    public List<HybridRetriever.RerankedResult> rerank(List<VectorStore.SimilarityResult> vectorResults, 
                                                      String query) {
        if (vectorResults.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Limit to manageable batch size
        List<VectorStore.SimilarityResult> candidates = vectorResults.stream()
                .limit(MAX_CHUNKS_PER_REQUEST)
                .collect(Collectors.toList());
        
        try {
            // Prepare chunks for LLM
            String chunksJson = prepareChunksForReranking(candidates);
            
            // Build prompt
            String prompt = rerankPrompt
                    .replace("{query}", query)
                    .replace("{code_chunks}", chunksJson);
            
            // Get LLM response
            String response = chatModel.generate(prompt);
            
            // Parse rankings
            List<ChunkRanking> rankings = parseRankingResponse(response);
            
            // Convert to RerankedResult with combined scores
            return mergeRankingsWithVectorScores(candidates, rankings, "llm_rerank");
            
        } catch (Exception e) {
            logger.error("LLM reranking failed, falling back to vector scores", e);
            
            // Fallback to vector-only ranking
            return vectorResults.stream()
                    .map(result -> new HybridRetriever.RerankedResult(
                            result.getChunk(),
                            result.getSimilarity(),
                            result.getSimilarity(), // Use vector score as LLM score
                            "vector_fallback"
                    ))
                    .collect(Collectors.toList());
        }
    }
    
    /**
     * Rerank results for pattern matching (used in retrofit)
     */
    public List<HybridRetriever.RerankedResult> rerankForPattern(
            List<VectorStore.SimilarityResult> vectorResults,
            String sourceCode,
            String targetHint,
            CodeChunk.ChunkType preferredType) {
        
        if (vectorResults.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<VectorStore.SimilarityResult> candidates = vectorResults.stream()
                .limit(MAX_CHUNKS_PER_REQUEST)
                .collect(Collectors.toList());
        
        try {
            // Prepare chunks with pattern context
            String chunksJson = prepareChunksForPatternReranking(candidates, preferredType);
            
            // Build pattern-specific prompt
            String enhancedQuery = buildPatternQuery(sourceCode, targetHint, preferredType);
            String prompt = patternRerankPrompt
                    .replace("{source_code}", truncateContent(sourceCode))
                    .replace("{target_hint}", targetHint != null ? targetHint : "")
                    .replace("{preferred_type}", preferredType.name())
                    .replace("{query}", enhancedQuery)
                    .replace("{code_chunks}", chunksJson);
            
            // Get LLM response
            String response = chatModel.generate(prompt);
            
            // Parse rankings
            List<ChunkRanking> rankings = parseRankingResponse(response);
            
            // Convert with pattern-specific weighting
            return mergeRankingsWithVectorScores(candidates, rankings, "pattern_rerank");
            
        } catch (Exception e) {
            logger.error("Pattern reranking failed, falling back to vector scores", e);
            
            return vectorResults.stream()
                    .map(result -> new HybridRetriever.RerankedResult(
                            result.getChunk(),
                            result.getSimilarity(),
                            result.getSimilarity(),
                            "pattern_fallback"
                    ))
                    .collect(Collectors.toList());
        }
    }
    
    /**
     * Prepare chunks for general reranking
     */
    private String prepareChunksForReranking(List<VectorStore.SimilarityResult> candidates) {
        List<Map<String, Object>> chunkData = new ArrayList<>();
        
        for (VectorStore.SimilarityResult result : candidates) {
            CodeChunk chunk = result.getChunk();
            Map<String, Object> chunkInfo = new HashMap<>();
            
            chunkInfo.put("chunk_id", chunk.getId());
            chunkInfo.put("type", chunk.getType().name());
            chunkInfo.put("fqn", chunk.getFullyQualifiedName());
            chunkInfo.put("file_path", chunk.getFilePath());
            chunkInfo.put("content", truncateContent(chunk.getContent()));
            chunkInfo.put("vector_similarity", result.getSimilarity());
            
            // Add context information
            if (chunk.getClassContext() != null) {
                chunkInfo.put("class_context", chunk.getClassContext());
            }
            if (!chunk.getApiCallSequence().isEmpty()) {
                chunkInfo.put("api_calls", chunk.getApiCallSequence());
            }
            if (!chunk.getMvcMapping().isEmpty()) {
                chunkInfo.put("mvc_mapping", chunk.getMvcMapping());
            }
            
            chunkData.add(chunkInfo);
        }
        
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(chunkData);
        } catch (Exception e) {
            logger.error("Failed to serialize chunks for reranking", e);
            return "[]";
        }
    }
    
    /**
     * Prepare chunks for pattern matching reranking
     */
    private String prepareChunksForPatternReranking(List<VectorStore.SimilarityResult> candidates,
                                                   CodeChunk.ChunkType preferredType) {
        List<Map<String, Object>> chunkData = new ArrayList<>();
        
        for (VectorStore.SimilarityResult result : candidates) {
            CodeChunk chunk = result.getChunk();
            Map<String, Object> chunkInfo = new HashMap<>();
            
            chunkInfo.put("chunk_id", chunk.getId());
            chunkInfo.put("type", chunk.getType().name());
            chunkInfo.put("type_match", chunk.getType() == preferredType);
            chunkInfo.put("fqn", chunk.getFullyQualifiedName());
            chunkInfo.put("content", truncateContent(chunk.getContent()));
            chunkInfo.put("vector_similarity", result.getSimilarity());
            
            // Enhanced context for pattern matching
            chunkInfo.put("imports", chunk.getImports());
            chunkInfo.put("annotations", chunk.getAnnotations());
            chunkInfo.put("api_calls", chunk.getApiCallSequence());
            chunkInfo.put("mvc_mapping", chunk.getMvcMapping());
            chunkInfo.put("metadata", chunk.getMetadata());
            
            chunkData.add(chunkInfo);
        }
        
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(chunkData);
        } catch (Exception e) {
            logger.error("Failed to serialize chunks for pattern reranking", e);
            return "[]";
        }
    }
    
    /**
     * Parse LLM ranking response
     */
    private List<ChunkRanking> parseRankingResponse(String response) {
        try {
            // Try to extract JSON from the response
            String jsonPart = extractJsonFromResponse(response);
            
            JsonNode root = objectMapper.readTree(jsonPart);
            JsonNode rankingsNode = root.get("rankings");
            
            if (rankingsNode == null || !rankingsNode.isArray()) {
                throw new IllegalArgumentException("Invalid ranking response format");
            }
            
            List<ChunkRanking> rankings = new ArrayList<>();
            for (JsonNode rankingNode : rankingsNode) {
                String chunkId = rankingNode.get("chunk_id").asText();
                int score = rankingNode.get("score").asInt();
                String reasoning = rankingNode.get("reasoning").asText();
                
                rankings.add(new ChunkRanking(chunkId, score, reasoning));
            }
            
            return rankings;
            
        } catch (Exception e) {
            logger.error("Failed to parse LLM ranking response: " + response, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Extract JSON portion from potentially mixed response
     */
    private String extractJsonFromResponse(String response) {
        // Find JSON block in response (handles markdown code blocks, etc.)
        int jsonStart = response.indexOf("{");
        int jsonEnd = response.lastIndexOf("}");
        
        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1);
        }
        
        return response.trim();
    }
    
    /**
     * Merge LLM rankings with vector similarity scores
     */
    private List<HybridRetriever.RerankedResult> mergeRankingsWithVectorScores(
            List<VectorStore.SimilarityResult> vectorResults,
            List<ChunkRanking> rankings,
            String rankingMethod) {
        
        // Create lookup map for LLM scores
        Map<String, ChunkRanking> rankingMap = rankings.stream()
                .collect(Collectors.toMap(ChunkRanking::getChunkId, r -> r));
        
        List<HybridRetriever.RerankedResult> results = new ArrayList<>();
        
        for (VectorStore.SimilarityResult vectorResult : vectorResults) {
            String chunkId = vectorResult.getChunkId();
            double vectorSimilarity = vectorResult.getSimilarity();
            
            // Get LLM score (normalize 0-10 to 0-1)
            double llmScore = rankingMap.containsKey(chunkId) ? 
                    rankingMap.get(chunkId).getScore() / 10.0 : vectorSimilarity;
            
            HybridRetriever.RerankedResult result = new HybridRetriever.RerankedResult(
                    vectorResult.getChunk(),
                    vectorSimilarity,
                    llmScore,
                    rankingMethod
            );
            
            results.add(result);
        }
        
        // Sort by combined score (vector + LLM)
        results.sort((a, b) -> Double.compare(b.getCombinedScore(), a.getCombinedScore()));
        
        return results;
    }
    
    /**
     * Build pattern-specific query
     */
    private String buildPatternQuery(String sourceCode, String targetHint, CodeChunk.ChunkType preferredType) {
        StringBuilder query = new StringBuilder();
        
        query.append("Find equivalent ").append(preferredType.name().toLowerCase()).append(" for: ");
        
        // Extract key identifiers
        List<String> identifiers = extractKeyIdentifiers(sourceCode);
        query.append(String.join(" ", identifiers));
        
        if (targetHint != null && !targetHint.trim().isEmpty()) {
            query.append(" ").append(targetHint);
        }
        
        return query.toString();
    }
    
    /**
     * Extract key identifiers from source code
     */
    private List<String> extractKeyIdentifiers(String code) {
        // Simple pattern matching for identifiers
        Set<String> identifiers = new HashSet<>();
        
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b[A-Z][a-zA-Z0-9]*\\b");
        java.util.regex.Matcher matcher = pattern.matcher(code);
        
        while (matcher.find()) {
            String identifier = matcher.group();
            if (identifier.length() > 2) {
                identifiers.add(identifier);
            }
        }
        
        return new ArrayList<>(identifiers).stream().limit(10).collect(Collectors.toList());
    }
    
    /**
     * Truncate content for LLM processing
     */
    private String truncateContent(String content) {
        if (content.length() <= MAX_CONTENT_LENGTH) {
            return content;
        }
        
        return content.substring(0, MAX_CONTENT_LENGTH) + "...";
    }
    
    /**
     * Load prompt template from resources
     */
    private String loadPromptTemplate(String templateName) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("prompts/" + templateName)) {
            if (is == null) {
                logger.warn("Prompt template not found: {}", templateName);
                return "Rank the provided code chunks by relevance to the query.";
            }
            return IOUtils.toString(is, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Failed to load prompt template: " + templateName, e);
            return "Rank the provided code chunks by relevance to the query.";
        }
    }
    
    /**
     * Container for chunk ranking information
     */
    private static class ChunkRanking {
        private final String chunkId;
        private final int score; // 0-10 scale
        private final String reasoning;
        
        public ChunkRanking(String chunkId, int score, String reasoning) {
            this.chunkId = chunkId;
            this.score = Math.max(0, Math.min(10, score)); // Clamp to 0-10
            this.reasoning = reasoning;
        }
        
        public String getChunkId() { return chunkId; }
        public int getScore() { return score; }
        public String getReasoning() { return reasoning; }
    }
}