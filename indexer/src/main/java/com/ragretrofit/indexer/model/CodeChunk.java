package com.ragretrofit.indexer.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a structured chunk of code with rich metadata for RAG retrieval.
 * Supports method-level chunking with context preservation.
 */
public class CodeChunk {
    
    public enum ChunkType {
        METHOD, CLASS, JSP_FRAGMENT, CONFIG_SECTION, IMPORT_BLOCK, FIELD
    }
    
    private final String id;
    private final String content;
    private final ChunkType type;
    private final String filePath;
    private final String fullyQualifiedName;
    private final int startLine;
    private final int endLine;
    private final List<String> imports;
    private final List<String> annotations;
    private final String classContext;
    private final String packageContext;
    private final List<String> apiCallSequence;
    private final Map<String, String> mvcMapping;
    private final Map<String, Object> metadata;
    
    @JsonCreator
    public CodeChunk(
            @JsonProperty("id") String id,
            @JsonProperty("content") String content,
            @JsonProperty("type") ChunkType type,
            @JsonProperty("filePath") String filePath,
            @JsonProperty("fullyQualifiedName") String fullyQualifiedName,
            @JsonProperty("startLine") int startLine,
            @JsonProperty("endLine") int endLine,
            @JsonProperty("imports") List<String> imports,
            @JsonProperty("annotations") List<String> annotations,
            @JsonProperty("classContext") String classContext,
            @JsonProperty("packageContext") String packageContext,
            @JsonProperty("apiCallSequence") List<String> apiCallSequence,
            @JsonProperty("mvcMapping") Map<String, String> mvcMapping,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.content = Objects.requireNonNull(content, "Content cannot be null");
        this.type = Objects.requireNonNull(type, "Type cannot be null");
        this.filePath = Objects.requireNonNull(filePath, "File path cannot be null");
        this.fullyQualifiedName = fullyQualifiedName;
        this.startLine = startLine;
        this.endLine = endLine;
        this.imports = imports != null ? List.copyOf(imports) : List.of();
        this.annotations = annotations != null ? List.copyOf(annotations) : List.of();
        this.classContext = classContext;
        this.packageContext = packageContext;
        this.apiCallSequence = apiCallSequence != null ? List.copyOf(apiCallSequence) : List.of();
        this.mvcMapping = mvcMapping != null ? Map.copyOf(mvcMapping) : Map.of();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
    
    // Builder pattern for easier construction
    public static class Builder {
        private String id;
        private String content;
        private ChunkType type;
        private String filePath;
        private String fullyQualifiedName;
        private int startLine;
        private int endLine;
        private List<String> imports;
        private List<String> annotations;
        private String classContext;
        private String packageContext;
        private List<String> apiCallSequence;
        private Map<String, String> mvcMapping;
        private Map<String, Object> metadata;
        
        public Builder id(String id) {
            this.id = id;
            return this;
        }
        
        public Builder content(String content) {
            this.content = content;
            return this;
        }
        
        public Builder type(ChunkType type) {
            this.type = type;
            return this;
        }
        
        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }
        
        public Builder fullyQualifiedName(String fullyQualifiedName) {
            this.fullyQualifiedName = fullyQualifiedName;
            return this;
        }
        
        public Builder startLine(int startLine) {
            this.startLine = startLine;
            return this;
        }
        
        public Builder endLine(int endLine) {
            this.endLine = endLine;
            return this;
        }
        
        public Builder imports(List<String> imports) {
            this.imports = imports;
            return this;
        }
        
        public Builder annotations(List<String> annotations) {
            this.annotations = annotations;
            return this;
        }
        
        public Builder classContext(String classContext) {
            this.classContext = classContext;
            return this;
        }
        
        public Builder packageContext(String packageContext) {
            this.packageContext = packageContext;
            return this;
        }
        
        public Builder apiCallSequence(List<String> apiCallSequence) {
            this.apiCallSequence = apiCallSequence;
            return this;
        }
        
        public Builder mvcMapping(Map<String, String> mvcMapping) {
            this.mvcMapping = mvcMapping;
            return this;
        }
        
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }
        
        public CodeChunk build() {
            return new CodeChunk(id, content, type, filePath, fullyQualifiedName,
                    startLine, endLine, imports, annotations, classContext,
                    packageContext, apiCallSequence, mvcMapping, metadata);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    // Getters
    public String getId() { return id; }
    public String getContent() { return content; }
    public ChunkType getType() { return type; }
    public String getFilePath() { return filePath; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public List<String> getImports() { return imports; }
    public List<String> getAnnotations() { return annotations; }
    public String getClassContext() { return classContext; }
    public String getPackageContext() { return packageContext; }
    public List<String> getApiCallSequence() { return apiCallSequence; }
    public Map<String, String> getMvcMapping() { return mvcMapping; }
    public Map<String, Object> getMetadata() { return metadata; }
    
    /**
     * Get searchable text combining content with context
     */
    public String getSearchableText() {
        StringBuilder sb = new StringBuilder();
        
        if (packageContext != null) {
            sb.append("package ").append(packageContext).append(";\n");
        }
        
        if (!imports.isEmpty()) {
            imports.forEach(imp -> sb.append("import ").append(imp).append(";\n"));
            sb.append("\n");
        }
        
        if (classContext != null) {
            sb.append("// Class context: ").append(classContext).append("\n");
        }
        
        if (!annotations.isEmpty()) {
            annotations.forEach(ann -> sb.append(ann).append("\n"));
        }
        
        sb.append(content);
        
        return sb.toString();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CodeChunk codeChunk = (CodeChunk) o;
        return Objects.equals(id, codeChunk.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "CodeChunk{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", filePath='" + filePath + '\'' +
                ", fqn='" + fullyQualifiedName + '\'' +
                ", lines=" + startLine + "-" + endLine +
                '}';
    }
}