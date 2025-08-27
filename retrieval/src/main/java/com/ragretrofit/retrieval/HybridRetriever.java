package com.ragretrofit.retrieval;

import com.ragretrofit.indexer.model.CodeChunk;
import com.ragretrofit.stores.lucene.LuceneBM25Store;
import com.ragretrofit.stores.vector.VectorStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.retriever.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval pipeline implementing: BM25 prefilter → graph filter → vector recall → LLM rerank.
 * Designed for high-precision code retrieval in large Java EE codebases.
 */
public class HybridRetriever implements Retriever<TextSegment> {
    
    private static final Logger logger = LoggerFactory.getLogger(HybridRetriever.class);
    
    private final LuceneBM25Store bm25Store;
    private final VectorStore vectorStore;
    private final CodeGraphFilter graphFilter;
    private final LLMReranker llmReranker;
    
    // Configuration
    private final int bm25PrefilterSize;
    private final int vectorRecallSize;
    private final int finalResultSize;
    private final double vectorSimilarityThreshold;
    
    public HybridRetriever(LuceneBM25Store bm25Store, 
                          VectorStore vectorStore,
                          CodeGraphFilter graphFilter,
                          String openAiApiKey) {
        this(bm25Store, vectorStore, graphFilter, openAiApiKey, 
             100, 50, 10, 0.3);
    }
    
    public HybridRetriever(LuceneBM25Store bm25Store,
                          VectorStore vectorStore,
                          CodeGraphFilter graphFilter,
                          String openAiApiKey,
                          int bm25PrefilterSize,
                          int vectorRecallSize,
                          int finalResultSize,
                          double vectorSimilarityThreshold) {
        
        this.bm25Store = Objects.requireNonNull(bm25Store, "BM25 store cannot be null");
        this.vectorStore = Objects.requireNonNull(vectorStore, "Vector store cannot be null");
        this.graphFilter = graphFilter; // Can be null for simple retrieval
        
        this.bm25PrefilterSize = bm25PrefilterSize;
        this.vectorRecallSize = vectorRecallSize;
        this.finalResultSize = finalResultSize;
        this.vectorSimilarityThreshold = vectorSimilarityThreshold;
        
        // Initialize LLM reranker
        if (openAiApiKey != null && !openAiApiKey.trim().isEmpty()) {
            ChatLanguageModel chatModel = OpenAiChatModel.builder()
                    .apiKey(openAiApiKey)
                    .modelName("gpt-3.5-turbo") // Cost-effective for reranking
                    .temperature(0.0) // Deterministic for ranking
                    .build();
            this.llmReranker = new LLMReranker(chatModel);
        } else {
            logger.warn("No OpenAI API key provided, LLM reranking will be disabled");
            this.llmReranker = null;
        }
        
        logger.info("Initialized HybridRetriever with BM25({}) → Vector({}) → LLM → Final({})",
                bm25PrefilterSize, vectorRecallSize, finalResultSize);
    }
    
    @Override
    public List<TextSegment> findRelevant(String query) {
        return findRelevant(query, finalResultSize);
    }
    
    /**
     * Main retrieval pipeline with configurable result size
     */
    public List<TextSegment> findRelevant(String query, int maxResults) {
        logger.debug("Starting hybrid retrieval for query: '{}'", 
                query.length() > 100 ? query.substring(0, 100) + "..." : query);
        
        try {
            // Stage 1: BM25 Prefilter
            List<LuceneBM25Store.SearchResult> bm25Results = performBM25Prefilter(query);
            if (bm25Results.isEmpty()) {
                logger.debug("No BM25 results found for query");
                return Collections.emptyList();
            }
            
            // Stage 2: Graph Filter (optional)
            List<LuceneBM25Store.SearchResult> graphFiltered = applyGraphFilter(bm25Results, query);
            
            // Stage 3: Vector Recall
            List<VectorStore.SimilarityResult> vectorResults = performVectorRecall(graphFiltered, query);
            
            // Stage 4: LLM Reranking
            List<RerankedResult> reranked = performLLMReranking(vectorResults, query);
            
            // Convert to TextSegments
            List<TextSegment> finalResults = reranked.stream()
                    .limit(maxResults)
                    .map(this::convertToTextSegment)
                    .collect(Collectors.toList());
            
            logger.debug("Hybrid retrieval completed: {} final results", finalResults.size());
            return finalResults;
            
        } catch (Exception e) {
            logger.error("Error during hybrid retrieval", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Find relevant code for a specific pattern (used in retrofit mapping)
     */
    public List<TextSegment> findRelevantForPattern(String sourceCode, String targetHint, 
                                                   CodeChunk.ChunkType preferredType) {
        
        // Combine source code context with target hint for better matching
        String enhancedQuery = buildEnhancedQuery(sourceCode, targetHint);
        
        try {
            // Use type-specific BM25 search
            List<LuceneBM25Store.SearchResult> bm25Results = bm25Store.search(
                    enhancedQuery, bm25PrefilterSize * 2, preferredType);
            
            // Apply graph filter with pattern-specific logic
            List<LuceneBM25Store.SearchResult> graphFiltered = applyPatternGraphFilter(
                    bm25Results, sourceCode, preferredType);
            
            // Vector recall with source code similarity
            List<VectorStore.SimilarityResult> vectorResults = performPatternVectorRecall(
                    graphFiltered, sourceCode);
            
            // LLM reranking with pattern context
            List<RerankedResult> reranked = performPatternLLMReranking(
                    vectorResults, sourceCode, targetHint, preferredType);
            
            return reranked.stream()
                    .limit(finalResultSize)
                    .map(this::convertToTextSegment)
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            logger.error("Error during pattern-based retrieval", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Stage 1: BM25 Prefiltering
     */
    private List<LuceneBM25Store.SearchResult> performBM25Prefilter(String query) throws IOException {
        List<LuceneBM25Store.SearchResult> results = bm25Store.search(query, bm25PrefilterSize);
        logger.debug("BM25 prefilter returned {} results", results.size());
        return results;
    }
    
    /**
     * Stage 2: Graph Filtering (optional)
     */
    private List<LuceneBM25Store.SearchResult> applyGraphFilter(
            List<LuceneBM25Store.SearchResult> bm25Results, String query) {
        
        if (graphFilter == null) {
            return bm25Results;
        }
        
        List<LuceneBM25Store.SearchResult> filtered = graphFilter.filterResults(bm25Results, query);
        logger.debug("Graph filter reduced results from {} to {}", bm25Results.size(), filtered.size());
        return filtered;
    }
    
    /**
     * Stage 3: Vector Recall
     */
    private List<VectorStore.SimilarityResult> performVectorRecall(
            List<LuceneBM25Store.SearchResult> candidates, String query) {
        
        // Get vector similarities for all candidates
        Set<String> candidateIds = candidates.stream()
                .map(LuceneBM25Store.SearchResult::getId)
                .collect(Collectors.toSet());
        
        List<VectorStore.SimilarityResult> vectorResults = vectorStore.findSimilar(
                query, vectorRecallSize, vectorSimilarityThreshold);
        
        // Filter to only include BM25 candidates (intersection)
        List<VectorStore.SimilarityResult> filtered = vectorResults.stream()
                .filter(result -> candidateIds.contains(result.getChunkId()))
                .collect(Collectors.toList());
        
        logger.debug("Vector recall: {} candidates → {} similar results", 
                candidates.size(), filtered.size());
        
        return filtered;
    }
    
    /**
     * Stage 4: LLM Reranking
     */
    private List<RerankedResult> performLLMReranking(
            List<VectorStore.SimilarityResult> vectorResults, String query) {
        
        if (llmReranker == null) {
            // Fallback to vector score-based ranking
            return vectorResults.stream()
                    .map(result -> new RerankedResult(
                            result.getChunk(),
                            result.getSimilarity(),
                            result.getSimilarity(), // Use vector similarity as LLM score
                            "vector_only"
                    ))
                    .collect(Collectors.toList());
        }
        
        List<RerankedResult> reranked = llmReranker.rerank(vectorResults, query);
        logger.debug("LLM reranking completed for {} candidates", vectorResults.size());
        return reranked;
    }
    
    /**
     * Pattern-specific retrieval methods
     */
    private String buildEnhancedQuery(String sourceCode, String targetHint) {
        StringBuilder query = new StringBuilder();
        
        // Extract key identifiers from source code
        List<String> identifiers = extractIdentifiers(sourceCode);
        query.append(String.join(" ", identifiers));
        
        if (targetHint != null && !targetHint.trim().isEmpty()) {
            query.append(" ").append(targetHint);
        }
        
        return query.toString();
    }
    
    private List<String> extractIdentifiers(String code) {
        // Simple identifier extraction (could be enhanced with AST parsing)
        List<String> identifiers = new ArrayList<>();
        
        // Match class names, method names, variable names
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b[A-Za-z][A-Za-z0-9_]*\\b");
        java.util.regex.Matcher matcher = pattern.matcher(code);
        
        while (matcher.find()) {
            String identifier = matcher.group();
            if (isJavaKeyword(identifier) || identifier.length() < 3) {
                continue;
            }
            identifiers.add(identifier);
        }
        
        return identifiers.stream().distinct().limit(20).collect(Collectors.toList());
    }
    
    private boolean isJavaKeyword(String word) {
        Set<String> keywords = Set.of("public", "private", "protected", "static", "final", 
                "class", "interface", "extends", "implements", "import", "package",
                "if", "else", "while", "for", "return", "new", "this", "super");
        return keywords.contains(word.toLowerCase());
    }
    
    private List<LuceneBM25Store.SearchResult> applyPatternGraphFilter(
            List<LuceneBM25Store.SearchResult> results, String sourceCode, CodeChunk.ChunkType preferredType) {
        
        if (graphFilter == null) {
            return results;
        }
        
        return graphFilter.filterForPattern(results, sourceCode, preferredType);
    }
    
    private List<VectorStore.SimilarityResult> performPatternVectorRecall(
            List<LuceneBM25Store.SearchResult> candidates, String sourceCode) {
        
        Set<String> candidateIds = candidates.stream()
                .map(LuceneBM25Store.SearchResult::getId)
                .collect(Collectors.toSet());
        
        List<VectorStore.SimilarityResult> vectorResults = vectorStore.findSimilar(
                sourceCode, vectorRecallSize * 2, vectorSimilarityThreshold * 0.8); // Lower threshold for patterns
        
        return vectorResults.stream()
                .filter(result -> candidateIds.contains(result.getChunkId()))
                .collect(Collectors.toList());
    }
    
    private List<RerankedResult> performPatternLLMReranking(
            List<VectorStore.SimilarityResult> vectorResults, String sourceCode, 
            String targetHint, CodeChunk.ChunkType preferredType) {
        
        if (llmReranker == null) {
            return vectorResults.stream()
                    .map(result -> new RerankedResult(
                            result.getChunk(),
                            result.getSimilarity(),
                            result.getSimilarity(),
                            "vector_only"
                    ))
                    .collect(Collectors.toList());
        }
        
        return llmReranker.rerankForPattern(vectorResults, sourceCode, targetHint, preferredType);
    }
    
    private TextSegment convertToTextSegment(RerankedResult result) {
        CodeChunk chunk = result.getChunk();
        
        // Build metadata
        Map<String, Object> metadataMap = new HashMap<>(chunk.getMetadata());
        metadataMap.put("chunk_id", chunk.getId());
        metadataMap.put("chunk_type", chunk.getType().name());
        metadataMap.put("file_path", chunk.getFilePath());
        metadataMap.put("fqn", chunk.getFullyQualifiedName());
        metadataMap.put("vector_similarity", result.getVectorSimilarity());
        metadataMap.put("llm_score", result.getLlmScore());
        metadataMap.put("ranking_method", result.getRankingMethod());
        metadataMap.put("start_line", chunk.getStartLine());
        metadataMap.put("end_line", chunk.getEndLine());
        
        Metadata metadata = new Metadata(metadataMap);
        
        return TextSegment.from(chunk.getContent(), metadata);
    }
    
    /**
     * Container for reranked results
     */
    public static class RerankedResult {
        private final CodeChunk chunk;
        private final double vectorSimilarity;
        private final double llmScore;
        private final String rankingMethod;
        
        public RerankedResult(CodeChunk chunk, double vectorSimilarity, 
                            double llmScore, String rankingMethod) {
            this.chunk = chunk;
            this.vectorSimilarity = vectorSimilarity;
            this.llmScore = llmScore;
            this.rankingMethod = rankingMethod;
        }
        
        public CodeChunk getChunk() { return chunk; }
        public double getVectorSimilarity() { return vectorSimilarity; }
        public double getLlmScore() { return llmScore; }
        public String getRankingMethod() { return rankingMethod; }
        
        public double getCombinedScore() {
            return (vectorSimilarity * 0.4) + (llmScore * 0.6);
        }
        
        @Override
        public String toString() {
            return String.format("RerankedResult{chunk=%s, vector=%.3f, llm=%.3f, method=%s}",
                    chunk.getId(), vectorSimilarity, llmScore, rankingMethod);
        }
    }
}