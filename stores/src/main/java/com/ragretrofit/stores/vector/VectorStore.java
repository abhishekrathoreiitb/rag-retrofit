package com.ragretrofit.stores.vector;

import com.ragretrofit.indexer.model.CodeChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Local vector store using MiniLM v2 embeddings for semantic code search.
 * Implements in-memory vector storage with disk persistence for fast retrieval.
 */
public class VectorStore implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(VectorStore.class);
    
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final Path persistencePath;
    
    // In-memory storage for fast retrieval
    private final Map<String, VectorEntry> vectorIndex;
    private final Map<String, CodeChunk> chunkIndex;
    
    public VectorStore(Path persistencePath) {
        this.persistencePath = persistencePath;
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        this.objectMapper = new ObjectMapper();
        this.vectorIndex = new ConcurrentHashMap<>();
        this.chunkIndex = new ConcurrentHashMap<>();
        
        loadFromDisk();
        
        logger.info("Initialized vector store with {} embeddings", vectorIndex.size());
    }
    
    /**
     * Add a code chunk to the vector store
     */
    public void addChunk(CodeChunk chunk) {
        try {
            // Generate embedding for searchable text
            String text = chunk.getSearchableText();
            Embedding embedding = embeddingModel.embed(text).content();
            
            VectorEntry entry = new VectorEntry(
                    chunk.getId(),
                    embedding.vectorAsList(),
                    System.currentTimeMillis()
            );
            
            vectorIndex.put(chunk.getId(), entry);
            chunkIndex.put(chunk.getId(), chunk);
            
            if (logger.isDebugEnabled()) {
                logger.debug("Added vector for chunk: {} (dimension: {})", 
                        chunk.getId(), embedding.dimension());
            }
            
        } catch (Exception e) {
            logger.error("Failed to add chunk to vector store: " + chunk.getId(), e);
        }
    }
    
    /**
     * Add multiple chunks in batch
     */
    public void addChunks(List<CodeChunk> chunks) {
        logger.info("Adding {} chunks to vector store", chunks.size());
        
        for (CodeChunk chunk : chunks) {
            addChunk(chunk);
        }
        
        saveToDisk();
        logger.info("Successfully added {} chunks to vector store", chunks.size());
    }
    
    /**
     * Find similar code chunks using cosine similarity
     */
    public List<SimilarityResult> findSimilar(String queryText, int maxResults) {
        return findSimilar(queryText, maxResults, 0.0);
    }
    
    /**
     * Find similar chunks with minimum similarity threshold
     */
    public List<SimilarityResult> findSimilar(String queryText, int maxResults, double minSimilarity) {
        try {
            // Generate query embedding
            Embedding queryEmbedding = embeddingModel.embed(queryText).content();
            List<Double> queryVector = queryEmbedding.vectorAsList();
            
            // Calculate similarities
            List<SimilarityResult> results = vectorIndex.entrySet().stream()
                    .map(entry -> {
                        String chunkId = entry.getKey();
                        VectorEntry vectorEntry = entry.getValue();
                        double similarity = cosineSimilarity(queryVector, vectorEntry.getVector());
                        
                        return new SimilarityResult(
                                chunkId,
                                chunkIndex.get(chunkId),
                                similarity
                        );
                    })
                    .filter(result -> result.getSimilarity() >= minSimilarity)
                    .sorted((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()))
                    .limit(maxResults)
                    .collect(Collectors.toList());
            
            logger.debug("Vector similarity search for '{}' returned {} results", 
                    queryText.substring(0, Math.min(50, queryText.length())), results.size());
            
            return results;
            
        } catch (Exception e) {
            logger.error("Error performing vector similarity search", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Find similar chunks by chunk ID
     */
    public List<SimilarityResult> findSimilarToChunk(String chunkId, int maxResults) {
        VectorEntry targetEntry = vectorIndex.get(chunkId);
        if (targetEntry == null) {
            logger.warn("Chunk not found in vector store: {}", chunkId);
            return Collections.emptyList();
        }
        
        List<Double> targetVector = targetEntry.getVector();
        
        return vectorIndex.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(chunkId)) // Exclude self
                .map(entry -> {
                    String otherChunkId = entry.getKey();
                    VectorEntry vectorEntry = entry.getValue();
                    double similarity = cosineSimilarity(targetVector, vectorEntry.getVector());
                    
                    return new SimilarityResult(
                            otherChunkId,
                            chunkIndex.get(otherChunkId),
                            similarity
                    );
                })
                .sorted((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()))
                .limit(maxResults)
                .collect(Collectors.toList());
    }
    
    /**
     * Find chunks similar to a given vector
     */
    public List<SimilarityResult> findSimilarToVector(List<Double> queryVector, int maxResults) {
        return vectorIndex.entrySet().stream()
                .map(entry -> {
                    String chunkId = entry.getKey();
                    VectorEntry vectorEntry = entry.getValue();
                    double similarity = cosineSimilarity(queryVector, vectorEntry.getVector());
                    
                    return new SimilarityResult(
                            chunkId,
                            chunkIndex.get(chunkId),
                            similarity
                    );
                })
                .sorted((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()))
                .limit(maxResults)
                .collect(Collectors.toList());
    }
    
    /**
     * Get chunk by ID
     */
    public CodeChunk getChunk(String chunkId) {
        return chunkIndex.get(chunkId);
    }
    
    /**
     * Check if chunk exists
     */
    public boolean containsChunk(String chunkId) {
        return vectorIndex.containsKey(chunkId);
    }
    
    /**
     * Remove chunk from store
     */
    public void removeChunk(String chunkId) {
        vectorIndex.remove(chunkId);
        chunkIndex.remove(chunkId);
        logger.debug("Removed chunk from vector store: {}", chunkId);
    }
    
    /**
     * Get total number of stored vectors
     */
    public int size() {
        return vectorIndex.size();
    }
    
    /**
     * Clear all vectors
     */
    public void clear() {
        vectorIndex.clear();
        chunkIndex.clear();
        saveToDisk();
        logger.info("Cleared vector store");
    }
    
    /**
     * Save vectors to disk for persistence
     */
    public void saveToDisk() {
        try {
            Files.createDirectories(persistencePath.getParent());
            
            VectorStoreData data = new VectorStoreData(
                    new HashMap<>(vectorIndex),
                    new HashMap<>(chunkIndex)
            );
            
            try (FileWriter writer = new FileWriter(persistencePath.toFile())) {
                objectMapper.writeValue(writer, data);
            }
            
            logger.debug("Saved {} vectors to disk", vectorIndex.size());
            
        } catch (Exception e) {
            logger.error("Failed to save vector store to disk", e);
        }
    }
    
    /**
     * Load vectors from disk
     */
    private void loadFromDisk() {
        if (!Files.exists(persistencePath)) {
            logger.info("No existing vector store found, starting fresh");
            return;
        }
        
        try {
            VectorStoreData data = objectMapper.readValue(persistencePath.toFile(), VectorStoreData.class);
            
            vectorIndex.clear();
            chunkIndex.clear();
            
            if (data.getVectorIndex() != null) {
                vectorIndex.putAll(data.getVectorIndex());
            }
            
            if (data.getChunkIndex() != null) {
                chunkIndex.putAll(data.getChunkIndex());
            }
            
            logger.info("Loaded {} vectors from disk", vectorIndex.size());
            
        } catch (Exception e) {
            logger.error("Failed to load vector store from disk", e);
        }
    }
    
    /**
     * Calculate cosine similarity between two vectors
     */
    private double cosineSimilarity(List<Double> vectorA, List<Double> vectorB) {
        if (vectorA.size() != vectorB.size()) {
            throw new IllegalArgumentException("Vectors must have the same dimension");
        }
        
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        for (int i = 0; i < vectorA.size(); i++) {
            double a = vectorA.get(i);
            double b = vectorB.get(i);
            
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }
        
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    @Override
    public void close() {
        saveToDisk();
        logger.info("Closed vector store");
    }
    
    /**
     * Vector entry for storage
     */
    public static class VectorEntry {
        private String chunkId;
        private List<Double> vector;
        private long timestamp;
        
        public VectorEntry() {} // For Jackson
        
        public VectorEntry(String chunkId, List<Double> vector, long timestamp) {
            this.chunkId = chunkId;
            this.vector = vector;
            this.timestamp = timestamp;
        }
        
        // Getters and setters
        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }
        
        public List<Double> getVector() { return vector; }
        public void setVector(List<Double> vector) { this.vector = vector; }
        
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
    
    /**
     * Similarity search result
     */
    public static class SimilarityResult {
        private final String chunkId;
        private final CodeChunk chunk;
        private final double similarity;
        
        public SimilarityResult(String chunkId, CodeChunk chunk, double similarity) {
            this.chunkId = chunkId;
            this.chunk = chunk;
            this.similarity = similarity;
        }
        
        public String getChunkId() { return chunkId; }
        public CodeChunk getChunk() { return chunk; }
        public double getSimilarity() { return similarity; }
        
        @Override
        public String toString() {
            return String.format("SimilarityResult{chunkId='%s', similarity=%.3f, type=%s}", 
                    chunkId, similarity, chunk != null ? chunk.getType() : "null");
        }
    }
    
    /**
     * Data structure for persistence
     */
    private static class VectorStoreData {
        private Map<String, VectorEntry> vectorIndex;
        private Map<String, CodeChunk> chunkIndex;
        
        public VectorStoreData() {} // For Jackson
        
        public VectorStoreData(Map<String, VectorEntry> vectorIndex, Map<String, CodeChunk> chunkIndex) {
            this.vectorIndex = vectorIndex;
            this.chunkIndex = chunkIndex;
        }
        
        public Map<String, VectorEntry> getVectorIndex() { return vectorIndex; }
        public void setVectorIndex(Map<String, VectorEntry> vectorIndex) { this.vectorIndex = vectorIndex; }
        
        public Map<String, CodeChunk> getChunkIndex() { return chunkIndex; }
        public void setChunkIndex(Map<String, CodeChunk> chunkIndex) { this.chunkIndex = chunkIndex; }
    }
}