package com.ragretrofit.retrofit.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Represents a file change from a diff, including metadata and structured hunks.
 */
public class FileChange {
    
    public enum ChangeType {
        ADDED, DELETED, MODIFIED, RENAMED
    }
    
    public enum FileType {
        JAVA, JSP, XML, CONFIG, PROPERTIES, SQL, JAVASCRIPT, CSS, OTHER
    }
    
    private final String oldPath;
    private final String newPath;
    private ChangeType changeType;
    private FileType fileType;
    private boolean binary;
    private int addedLines;
    private int deletedLines;
    private List<Hunk> hunks;
    
    @JsonCreator
    public FileChange(@JsonProperty("oldPath") String oldPath,
                     @JsonProperty("newPath") String newPath) {
        this.oldPath = Objects.requireNonNull(oldPath, "Old path cannot be null");
        this.newPath = Objects.requireNonNull(newPath, "New path cannot be null");
    }
    
    // Getters and setters
    public String getOldPath() { return oldPath; }
    public String getNewPath() { return newPath; }
    
    public ChangeType getChangeType() { return changeType; }
    public void setChangeType(ChangeType changeType) { this.changeType = changeType; }
    
    public FileType getFileType() { return fileType; }
    public void setFileType(FileType fileType) { this.fileType = fileType; }
    
    public boolean isBinary() { return binary; }
    public void setBinary(boolean binary) { this.binary = binary; }
    
    public int getAddedLines() { return addedLines; }
    public void setAddedLines(int addedLines) { this.addedLines = addedLines; }
    
    public int getDeletedLines() { return deletedLines; }
    public void setDeletedLines(int deletedLines) { this.deletedLines = deletedLines; }
    
    public List<Hunk> getHunks() { return hunks; }
    public void setHunks(List<Hunk> hunks) { this.hunks = hunks; }
    
    /**
     * Get the effective file path (new path for additions, old path for deletions)
     */
    public String getEffectivePath() {
        if (changeType == ChangeType.DELETED) {
            return oldPath;
        } else {
            return newPath;
        }
    }
    
    /**
     * Check if this is a Java source file
     */
    public boolean isJavaFile() {
        return fileType == FileType.JAVA;
    }
    
    /**
     * Check if this is a JSP file
     */
    public boolean isJspFile() {
        return fileType == FileType.JSP;
    }
    
    /**
     * Check if this is a configuration file
     */
    public boolean isConfigFile() {
        return fileType == FileType.CONFIG || fileType == FileType.XML;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileChange that = (FileChange) o;
        return Objects.equals(oldPath, that.oldPath) && 
               Objects.equals(newPath, that.newPath);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(oldPath, newPath);
    }
    
    @Override
    public String toString() {
        return String.format("FileChange{%s: %s -> %s, +%d -%d lines}", 
                changeType, oldPath, newPath, addedLines, deletedLines);
    }
}