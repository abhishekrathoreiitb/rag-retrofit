package com.ragretrofit.indexer.parser;

import com.ragretrofit.indexer.model.CodeChunk;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for parsing different types of source files into CodeChunks.
 * Supports Java, JSP, XML configuration files, etc.
 */
public interface CodeParser {
    
    /**
     * Check if this parser can handle the given file type
     */
    boolean canParse(Path filePath);
    
    /**
     * Parse the file into structured CodeChunks with rich metadata
     */
    List<CodeChunk> parse(Path filePath);
    
    /**
     * Get the priority of this parser (higher = preferred for conflicts)
     */
    default int getPriority() {
        return 0;
    }
}