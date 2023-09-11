package nigloo.gallerymanager.filter;

import nigloo.tool.parser.grammar.Token;
import nigloo.gallerymanager.model.Tag;

import java.nio.CharBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;


class ImageFilterTokenizer implements Iterator<Token<TokenType>> {

    private final CharSequence source;
    private int pos;
    private int line = 1;
    private int col = 1;

    public ImageFilterTokenizer(CharSequence source) {
        this.source = source;
        this.pos = 0;
        skipBlanks();
    }

    @Override
    public boolean hasNext() {
        return pos < source.length();
    }

    @Override
    public Token<TokenType> next() {
        if (!hasNext())
            throw new NoSuchElementException();

        char c = source.charAt(pos);

        Token<TokenType> token = switch (c) {
            case '-', '!' -> singleCharToken(TokenType.NEGATE);
            case '&' -> singleCharToken(TokenType.AND);
            case '|' -> singleCharToken(TokenType.OR);
            case '[' -> singleCharToken(TokenType.START_SUB_EXPRESSION);
            case ']' -> singleCharToken(TokenType.END_SUB_EXPRESSION);
            default -> {
                if (!Tag.isCharacterAllowed(c))
                    throw new IllegalArgumentException("Bad character '" + c + "' at " + pos);

                int posBegin = pos;
                while (pos < source.length() && Tag.isCharacterAllowed(source.charAt(pos)))
                    advance();

                // metatag (ex:  path:"path with spaces"
                if (pos < source.length() && source.charAt(pos) == ImageFilter.META_TAG_SEPARATOR) {
                    advance();

                    // Start with a "
                    if (pos < source.length() && source.charAt(pos) == ImageFilter.META_TAG_QUOTE) {
                        advance();
                        // Advance to the closing quotes
                        while (pos < source.length() && source.charAt(pos) != ImageFilter.META_TAG_QUOTE)
                            advance();

                        if (pos < source.length() && source.charAt(pos) == ImageFilter.META_TAG_QUOTE)
                            advance();
                    }
                    else {
                        while (pos < source.length() && !Character.isWhitespace(source.charAt(pos)))
                            advance();
                    }
                }

                yield new Token<>(TokenType.TAG_NAME, CharBuffer.wrap(source, posBegin, pos), posBegin, line, col);
            }
        };

        skipBlanks();

        return token;
    }

    private void skipBlanks() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos)))
            advance();
    }

    private Token<TokenType> singleCharToken(TokenType tokenType) {
        int offset = pos;
        advance();
        return new Token<>(tokenType, CharBuffer.wrap(source, offset, offset + 1), offset, line, col);
    }

    private void advance() {
        char c = source.charAt(pos++);
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
    }
}
