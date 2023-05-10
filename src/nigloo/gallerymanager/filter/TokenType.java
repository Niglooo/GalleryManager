package nigloo.gallerymanager.filter;

import nigloo.tool.parser.grammar.GrammarElement;

enum TokenType implements GrammarElement {
    TAG_NAME,
    NEGATE,
    AND,
    OR,
    START_SUB_EXPRESSION,
    END_SUB_EXPRESSION,
}
