package com.ragretrofit.stores.lucene;

import com.ragretrofit.indexer.model.CodeChunk;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lucene-based BM25 lexical search store for fast prefiltering of code chunks.
 * Optimized for Java source code and technical content.
 */
public class LuceneBM25Store implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(LuceneBM25Store.class);
    
    // Lucene field names
    private static final String FIELD_ID = "id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_SEARCHABLE_TEXT = "searchable_text";
    private static final String FIELD_FILE_PATH = "file_path";
    private static final String FIELD_FQN = "fully_qualified_name";
    private static final String FIELD_TYPE = "chunk_type";
    private static final String FIELD_CLASS_CONTEXT = "class_context";
    private static final String FIELD_PACKAGE_CONTEXT = "package_context";
    private static final String FIELD_API_CALLS = "api_calls";
    private static final String FIELD_MVC_MAPPING = "mvc_mapping";
    
    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter indexWriter;
    private IndexReader indexReader;
    private IndexSearcher indexSearcher;
    
    public LuceneBM25Store(Path indexPath) throws IOException {
        this.directory = FSDirectory.open(indexPath);
        this.analyzer = new StandardAnalyzer();
        
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setSimilarity(new BM25Similarity(1.2f, 0.75f)); // Standard BM25 parameters
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        
        this.indexWriter = new IndexWriter(directory, config);
        
        refreshSearcher();
        
        logger.info("Initialized Lucene BM25 store at: {}", indexPath);
    }
    
    /**
     * Index a code chunk for BM25 search
     */
    public void indexChunk(CodeChunk chunk) throws IOException {
        Document doc = createDocument(chunk);
        indexWriter.addDocument(doc);
        
        if (logger.isDebugEnabled()) {
            logger.debug("Indexed chunk: {} ({})", chunk.getId(), chunk.getType());
        }
    }
    
    /**
     * Index multiple chunks in batch
     */
    public void indexChunks(List<CodeChunk> chunks) throws IOException {
        logger.info("Indexing {} chunks in batch", chunks.size());
        
        for (CodeChunk chunk : chunks) {
            indexChunk(chunk);
        }
        
        commit();
        logger.info("Successfully indexed {} chunks", chunks.size());
    }
    
    /**
     * Search for chunks using BM25 scoring
     */
    public List<SearchResult> search(String queryText, int maxResults) throws IOException {
        return search(queryText, maxResults, null);
    }
    
    /**
     * Search with optional type filter
     */
    public List<SearchResult> search(String queryText, int maxResults, CodeChunk.ChunkType typeFilter) throws IOException {
        refreshSearcherIfNeeded();
        
        try {
            QueryParser parser = new QueryParser(FIELD_SEARCHABLE_TEXT, analyzer);
            Query query = parser.parse(QueryParser.escape(queryText));
            
            // Add type filter if specified
            if (typeFilter != null) {
                BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
                booleanQuery.add(query, BooleanClause.Occur.MUST);
                booleanQuery.add(new TermQuery(new Term(FIELD_TYPE, typeFilter.name())), BooleanClause.Occur.MUST);
                query = booleanQuery.build();
            }
            
            TopDocs topDocs = indexSearcher.search(query, maxResults);
            
            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = indexSearcher.doc(scoreDoc.doc);
                results.add(new SearchResult(
                        doc.get(FIELD_ID),
                        doc.get(FIELD_CONTENT),
                        scoreDoc.score,
                        doc.get(FIELD_FILE_PATH),
                        doc.get(FIELD_FQN),
                        CodeChunk.ChunkType.valueOf(doc.get(FIELD_TYPE))
                ));
            }
            
            logger.debug("BM25 search for '{}' returned {} results", queryText, results.size());
            return results;
            
        } catch (Exception e) {
            logger.error("Error performing BM25 search", e);
            throw new IOException("Search failed", e);
        }
    }
    
    /**
     * Search for chunks by fully qualified name pattern
     */
    public List<SearchResult> searchByFQN(String fqnPattern, int maxResults) throws IOException {
        refreshSearcherIfNeeded();
        
        try {
            QueryParser parser = new QueryParser(FIELD_FQN, analyzer);
            Query query = parser.parse(fqnPattern);
            
            TopDocs topDocs = indexSearcher.search(query, maxResults);
            
            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = indexSearcher.doc(scoreDoc.doc);
                results.add(new SearchResult(
                        doc.get(FIELD_ID),
                        doc.get(FIELD_CONTENT),
                        scoreDoc.score,
                        doc.get(FIELD_FILE_PATH),
                        doc.get(FIELD_FQN),
                        CodeChunk.ChunkType.valueOf(doc.get(FIELD_TYPE))
                ));
            }
            
            return results;
            
        } catch (Exception e) {
            logger.error("Error searching by FQN", e);
            throw new IOException("FQN search failed", e);
        }
    }
    
    /**
     * Search for chunks in specific file paths
     */
    public List<SearchResult> searchByFilePath(String pathPattern, int maxResults) throws IOException {
        refreshSearcherIfNeeded();
        
        try {
            QueryParser parser = new QueryParser(FIELD_FILE_PATH, analyzer);
            Query query = parser.parse(pathPattern);
            
            TopDocs topDocs = indexSearcher.search(query, maxResults);
            
            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = indexSearcher.doc(scoreDoc.doc);
                results.add(new SearchResult(
                        doc.get(FIELD_ID),
                        doc.get(FIELD_CONTENT),
                        scoreDoc.score,
                        doc.get(FIELD_FILE_PATH),
                        doc.get(FIELD_FQN),
                        CodeChunk.ChunkType.valueOf(doc.get(FIELD_TYPE))
                ));
            }
            
            return results;
            
        } catch (Exception e) {
            logger.error("Error searching by file path", e);
            throw new IOException("File path search failed", e);
        }
    }
    
    /**
     * Get total number of indexed documents
     */
    public int getDocumentCount() throws IOException {
        refreshSearcherIfNeeded();
        return indexSearcher.getIndexReader().numDocs();
    }
    
    /**
     * Commit all pending changes
     */
    public void commit() throws IOException {
        indexWriter.commit();
        refreshSearcher();
    }
    
    /**
     * Delete all documents matching the query
     */
    public void deleteByQuery(String queryText) throws IOException {
        try {
            QueryParser parser = new QueryParser(FIELD_SEARCHABLE_TEXT, analyzer);
            Query query = parser.parse(queryText);
            indexWriter.deleteDocuments(query);
            
        } catch (Exception e) {
            logger.error("Error deleting documents", e);
            throw new IOException("Delete failed", e);
        }
    }
    
    /**
     * Clear the entire index
     */
    public void clear() throws IOException {
        indexWriter.deleteAll();
        commit();
        logger.info("Cleared Lucene BM25 index");
    }
    
    private Document createDocument(CodeChunk chunk) {
        Document doc = new Document();
        
        // Core fields
        doc.add(new StringField(FIELD_ID, chunk.getId(), Field.Store.YES));
        doc.add(new TextField(FIELD_CONTENT, chunk.getContent(), Field.Store.YES));
        doc.add(new TextField(FIELD_SEARCHABLE_TEXT, chunk.getSearchableText(), Field.Store.NO));
        doc.add(new StringField(FIELD_FILE_PATH, chunk.getFilePath(), Field.Store.YES));
        doc.add(new StringField(FIELD_TYPE, chunk.getType().name(), Field.Store.YES));
        
        // Optional fields
        if (chunk.getFullyQualifiedName() != null) {
            doc.add(new TextField(FIELD_FQN, chunk.getFullyQualifiedName(), Field.Store.YES));
        }
        
        if (chunk.getClassContext() != null) {
            doc.add(new TextField(FIELD_CLASS_CONTEXT, chunk.getClassContext(), Field.Store.NO));
        }
        
        if (chunk.getPackageContext() != null) {
            doc.add(new TextField(FIELD_PACKAGE_CONTEXT, chunk.getPackageContext(), Field.Store.NO));
        }
        
        // Index API calls as searchable text
        if (!chunk.getApiCallSequence().isEmpty()) {
            String apiCalls = String.join(" ", chunk.getApiCallSequence());
            doc.add(new TextField(FIELD_API_CALLS, apiCalls, Field.Store.NO));
        }
        
        // Index MVC mappings
        if (!chunk.getMvcMapping().isEmpty()) {
            String mvcText = String.join(" ", chunk.getMvcMapping().values());
            doc.add(new TextField(FIELD_MVC_MAPPING, mvcText, Field.Store.NO));
        }
        
        return doc;
    }
    
    private void refreshSearcher() throws IOException {
        if (indexReader != null) {
            indexReader.close();
        }
        
        indexReader = DirectoryReader.open(directory);
        indexSearcher = new IndexSearcher(indexReader);
        indexSearcher.setSimilarity(new BM25Similarity(1.2f, 0.75f));
    }
    
    private void refreshSearcherIfNeeded() throws IOException {
        if (indexReader == null || !indexReader.isCurrent()) {
            refreshSearcher();
        }
    }
    
    @Override
    public void close() throws IOException {
        if (indexWriter != null) {
            indexWriter.close();
        }
        if (indexReader != null) {
            indexReader.close();
        }
        if (directory != null) {
            directory.close();
        }
        
        logger.info("Closed Lucene BM25 store");
    }
    
    /**
     * Search result container
     */
    public static class SearchResult {
        private final String id;
        private final String content;
        private final float score;
        private final String filePath;
        private final String fullyQualifiedName;
        private final CodeChunk.ChunkType type;
        
        public SearchResult(String id, String content, float score, String filePath, 
                          String fullyQualifiedName, CodeChunk.ChunkType type) {
            this.id = id;
            this.content = content;
            this.score = score;
            this.filePath = filePath;
            this.fullyQualifiedName = fullyQualifiedName;
            this.type = type;
        }
        
        // Getters
        public String getId() { return id; }
        public String getContent() { return content; }
        public float getScore() { return score; }
        public String getFilePath() { return filePath; }
        public String getFullyQualifiedName() { return fullyQualifiedName; }
        public CodeChunk.ChunkType getType() { return type; }
        
        @Override
        public String toString() {
            return String.format("SearchResult{id='%s', score=%.3f, type=%s, fqn='%s'}", 
                    id, score, type, fullyQualifiedName);
        }
    }
}