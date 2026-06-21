package com.dinesh.rag.agent.domain.file;

import java.util.Set;

/**
 * Coarse classification of an upload — used for display + (eventually)
 * choosing a smarter chunker per family. Tika does the real parsing
 * regardless of this value.
 */
public enum FileType {

    PDF,
    DOCX,
    DOC,
    TXT,
    MD,
    CODE,
    OTHER;

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java", ".kt", ".py", ".js", ".ts", ".go", ".rs",
            ".c", ".h", ".cpp", ".hpp", ".cs", ".rb", ".php",
            ".sql", ".sh", ".bash"
    );

    /**
     * Detect a {@link FileType} from the original filename. Falls back to
     * {@link #OTHER} for anything we don't have a specific bucket for.
     */
    public static FileType fromFilename(String filename) {
        if (filename == null) return OTHER;
        String lower = filename.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return OTHER;
        String ext = lower.substring(dot);

        return switch (ext) {
            case ".pdf" -> PDF;
            case ".docx" -> DOCX;
            case ".doc" -> DOC;
            case ".txt" -> TXT;
            case ".md", ".markdown" -> MD;
            default -> CODE_EXTENSIONS.contains(ext) ? CODE : OTHER;
        };
    }
}
