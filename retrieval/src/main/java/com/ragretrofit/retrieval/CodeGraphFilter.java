package com.ragretrofit.retrieval;

import com.ragretrofit.indexer.model.CodeChunk;
import com.ragretrofit.stores.lucene.LuceneBM25Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Graph-based filtering for code retrieval results.
 * Uses code relationships (inheritance, calls, imports, MVC mappings) to improve retrieval precision.
 */
public class CodeGraphFilter {
    
    private static final Logger logger = LoggerFactory.getLogger(CodeGraphFilter.class);
    
    // In-memory graph representation (could be replaced with graph database for larger scale)
    private final Map<String, Set<String>> callGraph;        // caller -> callees
    private final Map<String, Set<String>> inheritanceGraph; // subclass -> superclasses
    private final Map<String, Set<String>> importGraph;      // class -> imported classes
    private final Map<String, Set<String>> mvcGraph;         // action -> forms/jsps
    private final Map<String, Set<String>> packageGraph;     // class -> package members
    
    public CodeGraphFilter() {
        this.callGraph = new HashMap<>();
        this.inheritanceGraph = new HashMap<>();
        this.importGraph = new HashMap<>();
        this.mvcGraph = new HashMap<>();
        this.packageGraph = new HashMap<>();
        
        logger.info("Initialized code graph filter");
    }
    
    /**
     * Build graph relationships from code chunks
     */
    public void buildGraph(List<CodeChunk> chunks) {
        logger.info("Building code graph from {} chunks", chunks.size());
        
        // Clear existing graphs
        callGraph.clear();
        inheritanceGraph.clear();
        importGraph.clear();
        mvcGraph.clear();
        packageGraph.clear();
        
        for (CodeChunk chunk : chunks) {
            String chunkId = chunk.getId();
            String fqn = chunk.getFullyQualifiedName();
            
            // Build call relationships
            if (!chunk.getApiCallSequence().isEmpty()) {
                callGraph.putIfAbsent(chunkId, new HashSet<>());
                callGraph.get(chunkId).addAll(chunk.getApiCallSequence());
            }
            
            // Build import relationships
            if (!chunk.getImports().isEmpty()) {
                importGraph.putIfAbsent(chunkId, new HashSet<>());
                importGraph.get(chunkId).addAll(chunk.getImports());
            }
            
            // Build package relationships
            if (chunk.getPackageContext() != null) {
                packageGraph.putIfAbsent(chunk.getPackageContext(), new HashSet<>());
                packageGraph.get(chunk.getPackageContext()).add(chunkId);
            }
            
            // Build MVC relationships
            if (!chunk.getMvcMapping().isEmpty()) {
                for (Map.Entry<String, String> entry : chunk.getMvcMapping().entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    
                    if (key.contains("action") || key.contains("form") || key.contains("jsp")) {
                        mvcGraph.putIfAbsent(chunkId, new HashSet<>());
                        mvcGraph.get(chunkId).add(value);
                    }
                }
            }
            
            // Build inheritance relationships (from class context or annotations)
            if (chunk.getClassContext() != null) {
                extractInheritanceInfo(chunk, chunkId);
            }
        }
        
        logger.info("Built graph with {} call edges, {} import edges, {} MVC edges",
                callGraph.size(), importGraph.size(), mvcGraph.size());
    }
    
    /**
     * Filter BM25 results using graph relationships
     */
    public List<LuceneBM25Store.SearchResult> filterResults(
            List<LuceneBM25Store.SearchResult> bm25Results, String query) {
        
        if (bm25Results.isEmpty()) {
            return bm25Results;
        }
        
        // Extract context from query
        QueryContext context = analyzeQuery(query);
        
        // Score each result based on graph relationships
        List<ScoredResult> scoredResults = new ArrayList<>();
        
        for (LuceneBM25Store.SearchResult result : bm25Results) {
            double graphScore = calculateGraphScore(result, context, bm25Results);
            scoredResults.add(new ScoredResult(result, graphScore));
        }
        
        // Sort by combined BM25 + graph score
        scoredResults.sort((a, b) -> Double.compare(b.getCombinedScore(), a.getCombinedScore()));
        
        // Return filtered results (keep top 80% or max original size)
        int keepCount = Math.min(bm25Results.size(), (int) (bm25Results.size() * 0.8));
        
        List<LuceneBM25Store.SearchResult> filtered = scoredResults.stream()
                .limit(keepCount)
                .map(ScoredResult::getResult)
                .collect(Collectors.toList());
        
        logger.debug("Graph filter: {} -> {} results", bm25Results.size(), filtered.size());
        return filtered;
    }
    
    /**
     * Filter results for pattern matching with additional context
     */
    public List<LuceneBM25Store.SearchResult> filterForPattern(
            List<LuceneBM25Store.SearchResult> results, String sourceCode, CodeChunk.ChunkType preferredType) {
        
        if (results.isEmpty()) {
            return results;
        }
        
        // Extract patterns from source code
        PatternContext pattern = extractPatternContext(sourceCode, preferredType);
        
        // Score based on pattern matching
        List<ScoredResult> scoredResults = new ArrayList<>();
        
        for (LuceneBM25Store.SearchResult result : results) {
            double patternScore = calculatePatternScore(result, pattern);
            scoredResults.add(new ScoredResult(result, patternScore));
        }
        
        // Sort by pattern relevance
        scoredResults.sort((a, b) -> Double.compare(b.getGraphScore(), a.getGraphScore()));
        
        // Keep top results
        int keepCount = Math.min(results.size(), Math.max(5, results.size() / 2));
        
        return scoredResults.stream()
                .limit(keepCount)
                .map(ScoredResult::getResult)
                .collect(Collectors.toList());
    }
    
    /**
     * Calculate graph-based relevance score
     */
    private double calculateGraphScore(LuceneBM25Store.SearchResult result, QueryContext context,
                                     List<LuceneBM25Store.SearchResult> allResults) {
        
        double score = 0.0;
        String resultId = result.getId();
        
        // Package proximity score
        if (context.getPackageHints().contains(extractPackage(result.getFullyQualifiedName()))) {
            score += 0.3;
        }
        
        // Call relationship score
        Set<String> resultCalls = callGraph.getOrDefault(resultId, Collections.emptySet());
        long callMatches = context.getApiHints().stream()
                .mapToLong(api -> resultCalls.contains(api) ? 1 : 0)
                .sum();
        if (callMatches > 0) {
            score += 0.4 * (callMatches / (double) Math.max(1, context.getApiHints().size()));
        }
        
        // Import relationship score  
        Set<String> resultImports = importGraph.getOrDefault(resultId, Collections.emptySet());
        long importMatches = context.getImportHints().stream()
                .mapToLong(imp -> resultImports.contains(imp) ? 1 : 0)
                .sum();
        if (importMatches > 0) {
            score += 0.2 * (importMatches / (double) Math.max(1, context.getImportHints().size()));
        }
        
        // MVC relationship score
        Set<String> resultMvc = mvcGraph.getOrDefault(resultId, Collections.emptySet());
        long mvcMatches = context.getMvcHints().stream()
                .mapToLong(mvc -> resultMvc.contains(mvc) ? 1 : 0)
                .sum();
        if (mvcMatches > 0) {
            score += 0.3 * (mvcMatches / (double) Math.max(1, context.getMvcHints().size()));
        }
        
        // Co-occurrence boost (appears with other high-scoring results)
        long coOccurrences = countCoOccurrences(resultId, allResults);
        if (coOccurrences > 1) {
            score += 0.1 * Math.log(coOccurrences);
        }
        
        return Math.min(1.0, score); // Normalize to [0, 1]
    }
    
    /**
     * Calculate pattern-specific score
     */
    private double calculatePatternScore(LuceneBM25Store.SearchResult result, PatternContext pattern) {
        double score = 0.0;
        String resultId = result.getId();
        
        // Type matching bonus
        if (result.getType() == pattern.getPreferredType()) {
            score += 0.4;
        }
        
        // API pattern matching
        Set<String> resultCalls = callGraph.getOrDefault(resultId, Collections.emptySet());
        long apiMatches = pattern.getApiPatterns().stream()
                .mapToLong(api -> resultCalls.stream().anyMatch(call -> call.contains(api)) ? 1 : 0)
                .sum();
        if (apiMatches > 0) {
            score += 0.3 * (apiMatches / (double) Math.max(1, pattern.getApiPatterns().size()));
        }
        
        // Signature similarity
        if (pattern.getSignatureHints().stream()
                .anyMatch(sig -> result.getFullyQualifiedName().contains(sig))) {
            score += 0.2;
        }
        
        // Framework pattern matching
        Set<String> resultImports = importGraph.getOrDefault(resultId, Collections.emptySet());
        boolean frameworkMatch = pattern.getFrameworkHints().stream()
                .anyMatch(fw -> resultImports.stream().anyMatch(imp -> imp.contains(fw)));
        if (frameworkMatch) {
            score += 0.1;
        }
        
        return Math.min(1.0, score);
    }
    
    /**
     * Analyze query to extract context hints
     */
    private QueryContext analyzeQuery(String query) {
        Set<String> packageHints = new HashSet<>();
        Set<String> apiHints = new HashSet<>();
        Set<String> importHints = new HashSet<>();
        Set<String> mvcHints = new HashSet<>();
        
        String queryLower = query.toLowerCase();
        
        // Extract API hints
        if (queryLower.contains("service")) apiHints.add("Service");
        if (queryLower.contains("dao")) apiHints.add("DAO");
        if (queryLower.contains("controller")) apiHints.add("Controller");
        if (queryLower.contains("action")) apiHints.add("Action");
        
        // Extract package hints from common patterns
        if (queryLower.contains("util")) packageHints.add("util");
        if (queryLower.contains("service")) packageHints.add("service");
        if (queryLower.contains("dao")) packageHints.add("dao");
        if (queryLower.contains("web")) packageHints.add("web");
        
        // Extract MVC hints
        if (queryLower.contains("login")) mvcHints.add("login");
        if (queryLower.contains("user")) mvcHints.add("user");
        if (queryLower.contains("form")) mvcHints.add("form");
        
        return new QueryContext(packageHints, apiHints, importHints, mvcHints);
    }
    
    /**
     * Extract pattern context from source code
     */
    private PatternContext extractPatternContext(String sourceCode, CodeChunk.ChunkType preferredType) {
        Set<String> apiPatterns = new HashSet<>();
        Set<String> signatureHints = new HashSet<>();
        Set<String> frameworkHints = new HashSet<>();
        
        // Extract method names and API calls
        java.util.regex.Pattern methodPattern = java.util.regex.Pattern.compile("\\w+\\s*\\(");
        java.util.regex.Matcher matcher = methodPattern.matcher(sourceCode);
        while (matcher.find()) {
            String method = matcher.group().replaceAll("\\s*\\($", "");
            if (method.length() > 2) {
                apiPatterns.add(method);
            }
        }
        
        // Extract class/interface names
        java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile("\\b[A-Z][a-zA-Z0-9]*\\b");
        matcher = classPattern.matcher(sourceCode);
        while (matcher.find()) {
            String className = matcher.group();
            if (className.length() > 2) {
                signatureHints.add(className);
            }
        }
        
        // Extract framework patterns
        if (sourceCode.contains("struts")) frameworkHints.add("struts");
        if (sourceCode.contains("spring")) frameworkHints.add("spring");
        if (sourceCode.contains("hibernate")) frameworkHints.add("hibernate");
        if (sourceCode.contains("jsp")) frameworkHints.add("jsp");
        
        return new PatternContext(preferredType, apiPatterns, signatureHints, frameworkHints);
    }
    
    // Helper methods
    private void extractInheritanceInfo(CodeChunk chunk, String chunkId) {
        String classContext = chunk.getClassContext();
        
        if (classContext.contains("extends")) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("extends\\s+(\\w+)");
            java.util.regex.Matcher matcher = pattern.matcher(classContext);
            if (matcher.find()) {
                String superClass = matcher.group(1);
                inheritanceGraph.putIfAbsent(chunkId, new HashSet<>());
                inheritanceGraph.get(chunkId).add(superClass);
            }
        }
        
        if (classContext.contains("implements")) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("implements\\s+([\\w,\\s]+)");
            java.util.regex.Matcher matcher = pattern.matcher(classContext);
            if (matcher.find()) {
                String interfaces = matcher.group(1);
                String[] interfaceList = interfaces.split(",");
                inheritanceGraph.putIfAbsent(chunkId, new HashSet<>());
                for (String iface : interfaceList) {
                    inheritanceGraph.get(chunkId).add(iface.trim());
                }
            }
        }
    }
    
    private String extractPackage(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return "";
        }
        int lastDot = fqn.lastIndexOf('.');
        return fqn.substring(0, lastDot);
    }
    
    private long countCoOccurrences(String resultId, List<LuceneBM25Store.SearchResult> allResults) {
        String packageName = extractPackage(allResults.stream()
                .filter(r -> r.getId().equals(resultId))
                .findFirst()
                .map(LuceneBM25Store.SearchResult::getFullyQualifiedName)
                .orElse(""));
        
        return allResults.stream()
                .filter(r -> !r.getId().equals(resultId))
                .filter(r -> extractPackage(r.getFullyQualifiedName()).equals(packageName))
                .count();
    }
    
    // Data classes
    private static class QueryContext {
        private final Set<String> packageHints;
        private final Set<String> apiHints;
        private final Set<String> importHints;
        private final Set<String> mvcHints;
        
        public QueryContext(Set<String> packageHints, Set<String> apiHints, 
                          Set<String> importHints, Set<String> mvcHints) {
            this.packageHints = packageHints;
            this.apiHints = apiHints;
            this.importHints = importHints;
            this.mvcHints = mvcHints;
        }
        
        public Set<String> getPackageHints() { return packageHints; }
        public Set<String> getApiHints() { return apiHints; }
        public Set<String> getImportHints() { return importHints; }
        public Set<String> getMvcHints() { return mvcHints; }
    }
    
    private static class PatternContext {
        private final CodeChunk.ChunkType preferredType;
        private final Set<String> apiPatterns;
        private final Set<String> signatureHints;
        private final Set<String> frameworkHints;
        
        public PatternContext(CodeChunk.ChunkType preferredType, Set<String> apiPatterns,
                            Set<String> signatureHints, Set<String> frameworkHints) {
            this.preferredType = preferredType;
            this.apiPatterns = apiPatterns;
            this.signatureHints = signatureHints;
            this.frameworkHints = frameworkHints;
        }
        
        public CodeChunk.ChunkType getPreferredType() { return preferredType; }
        public Set<String> getApiPatterns() { return apiPatterns; }
        public Set<String> getSignatureHints() { return signatureHints; }
        public Set<String> getFrameworkHints() { return frameworkHints; }
    }
    
    private static class ScoredResult {
        private final LuceneBM25Store.SearchResult result;
        private final double graphScore;
        
        public ScoredResult(LuceneBM25Store.SearchResult result, double graphScore) {
            this.result = result;
            this.graphScore = graphScore;
        }
        
        public LuceneBM25Store.SearchResult getResult() { return result; }
        public double getGraphScore() { return graphScore; }
        
        public double getCombinedScore() {
            // Combine BM25 score with graph score (weighted)
            return (result.getScore() * 0.7) + (graphScore * 0.3);
        }
    }
}