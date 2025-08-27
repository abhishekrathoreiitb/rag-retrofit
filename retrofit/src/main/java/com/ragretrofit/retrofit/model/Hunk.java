package com.ragretrofit.retrofit.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Represents a hunk (contiguous block of changes) within a file diff.
 */
public class Hunk {
    
    private final int oldStart;
    private final int oldCount;
    private final int newStart;
    private final int newCount;
    private final String context;
    private List<String> lines;
    
    @JsonCreator
    public Hunk(@JsonProperty("oldStart") int oldStart,
               @JsonProperty("oldCount") int oldCount,
               @JsonProperty("newStart") int newStart,
               @JsonProperty("newCount") int newCount,
               @JsonProperty("context") String context) {
        this.oldStart = oldStart;
        this.oldCount = oldCount;
        this.newStart = newStart;
        this.newCount = newCount;
        this.context = context != null ? context : "";
    }
    
    // Getters and setters
    public int getOldStart() { return oldStart; }
    public int getOldCount() { return oldCount; }
    public int getNewStart() { return newStart; }
    public int getNewCount() { return newCount; }
    public String getContext() { return context; }
    
    public List<String> getLines() { return lines; }
    public void setLines(List<String> lines) { this.lines = lines; }
    
    /**
     * Get only the added lines (starting with +)
     */
    public List<String> getAddedLines() {
        return lines.stream()
                .filter(line -> line.startsWith("+") && !line.startsWith("+++"))
                .map(line -> line.substring(1)) // Remove + prefix
                .toList();
    }
    
    /**
     * Get only the deleted lines (starting with -)
     */
    public List<String> getDeletedLines() {
        return lines.stream()
                .filter(line -> line.startsWith("-") && !line.startsWith("---"))
                .map(line -> line.substring(1)) // Remove - prefix
                .toList();
    }
    
    /**
     * Get only the context lines (starting with space or no prefix)
     */
    public List<String> getContextLines() {
        return lines.stream()
                .filter(line -> line.startsWith(" ") || 
                              (!line.startsWith("+") && !line.startsWith("-")))
                .map(line -> line.startsWith(" ") ? line.substring(1) : line)
                .toList();
    }
    
    /**
     * Get the range of old lines affected by this hunk
     */
    public Range getOldRange() {
        return new Range(oldStart, oldStart + oldCount - 1);
    }
    
    /**
     * Get the range of new lines affected by this hunk
     */
    public Range getNewRange() {
        return new Range(newStart, newStart + newCount - 1);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Hunk hunk = (Hunk) o;
        return oldStart == hunk.oldStart &&
               oldCount == hunk.oldCount &&
               newStart == hunk.newStart &&
               newCount == hunk.newCount &&
               Objects.equals(context, hunk.context);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(oldStart, oldCount, newStart, newCount, context);
    }
    
    @Override
    public String toString() {
        return String.format("Hunk{old: %d,%d, new: %d,%d, context: '%s'}", 
                oldStart, oldCount, newStart, newCount, context);
    }
    
    /**
     * Simple range representation
     */
    public static class Range {
        private final int start;
        private final int end;
        
        public Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
        
        public int getStart() { return start; }
        public int getEnd() { return end; }
        public int size() { return end - start + 1; }
        
        public boolean contains(int line) {
            return line >= start && line <= end;
        }
        
        public boolean overlaps(Range other) {
            return this.start <= other.end && other.start <= this.end;
        }
        
        @Override
        public String toString() {
            return String.format("%d-%d", start, end);
        }
    }
}