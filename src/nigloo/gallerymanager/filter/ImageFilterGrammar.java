package nigloo.gallerymanager.filter;

import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.model.Tag;
import nigloo.tool.Utils;
import nigloo.tool.parser.grammar.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;

class ImageFilterGrammar {
    public static final CompiledGrammar<TokenType, ImageFilter> COMPILED_GRAMMAR;

    static {
        Grammar<TokenType, ImageFilter> grammar= buildGrammar();
        COMPILED_GRAMMAR = LL1GrammarCompiler.compileGrammar(grammar);
    }


    @SuppressWarnings("RedundantCollectionOperation")
    private static Grammar<TokenType, ImageFilter> buildGrammar() {
        final GrammarRule<ImageFilter> EXPR = new GrammarRule<>("EXPR", new ArrayList<>());
        final GrammarRule<ImageFilter> OR_EXPR = new GrammarRule<>("OR_EXPR", new ArrayList<>());
        final GrammarRule<ImageFilter> OR_EXPR2 = new GrammarRule<>("OR_EXPR2", new ArrayList<>());
        final GrammarRule<ImageFilter> AND_EXPR = new GrammarRule<>("AND_EXPR", new ArrayList<>());
        final GrammarRule<ImageFilter> AND_EXPR2 = new GrammarRule<>("AND_EXPR2", new ArrayList<>());
        final GrammarRule<ImageFilter> PRIMARY_EXPR = new GrammarRule<>("PRIMARY_EXPR", new ArrayList<>());

        /*
            expr ::= or_expr
         */
        EXPR.possibleSequences().addAll(List.of(
                new GrammarSequence<>(List.of(OR_EXPR), forward(0))
        ));

        /*
            or_expr ::= and_expr or_expr2
            or_expr2 ::= OR and_expr or_expr2
            or_expr2 ::= ''
         */
        OR_EXPR.possibleSequences().addAll(List.of(
                new GrammarSequence<>(List.of(AND_EXPR, OR_EXPR2), biOperator(0, 1, OrFilter::new))
        ));
        OR_EXPR2.possibleSequences().addAll(List.of(
                new GrammarSequence<>(List.of(TokenType.OR, AND_EXPR, OR_EXPR2), biOperator(1, 2, OrFilter::new)),
                new GrammarSequence<>(List.of(), evaluatedTokens -> null)
        ));

        /*
            and_expr ::= primary_expr and_expr2
            and_expr2 ::= AND primary_expr and_expr2
            and_expr2 ::= primary_expr and_expr2
            and_expr2 ::= ''
         */
        AND_EXPR.possibleSequences().addAll(List.of(
                new GrammarSequence<>(List.of(PRIMARY_EXPR, AND_EXPR2), biOperator(0, 1, AndFilter::new))
        ));
        AND_EXPR2.possibleSequences().addAll(List.of(
                new GrammarSequence<>(List.of(TokenType.AND, PRIMARY_EXPR, AND_EXPR2), biOperator(1, 2, AndFilter::new)),
                new GrammarSequence<>(List.of(PRIMARY_EXPR, AND_EXPR2), biOperator(0, 1, AndFilter::new)),
                new GrammarSequence<>(List.of(), evaluatedTokens -> null)
        ));

        /*
            primary_expr ::= tag
            primary_expr ::= - primary_expr
            primary_expr ::= ( expr )
         */
        PRIMARY_EXPR.possibleSequences().addAll(List.of(
                new GrammarSequence<>(List.of(TokenType.TAG_NAME), evaluatedTokens -> {
                    Token<?> token = (Token<?>) evaluatedTokens.get(0);
                    String value = token.value().toString();
                    int posSep = value.indexOf(ImageFilter.META_TAG_SEPARATOR);
                    if (posSep >= 0) {
                        String metaTagType = value.substring(0, posSep);
                        if (ImageFilter.META_TAG_TYPE_PATH.equalsIgnoreCase(metaTagType)) {
                            String path = value.substring(posSep + 1).replace(String.valueOf(ImageFilter.META_TAG_QUOTE), "");
                            return new PathFilter(PathFilter.normalizePath(path));
                        }
                        else {
                            throw new IllegalArgumentException("Invalid metatag: "+metaTagType);
                        }
                    }
                    else {
                        String normalisedTag = Tag.normalize(value);
                        return new TagFilter(normalisedTag);
                    }
                }),
                new GrammarSequence<>(List.of(TokenType.NEGATE, PRIMARY_EXPR), evaluatedTokens -> {
                    ImageFilter filter = (ImageFilter) evaluatedTokens.get(1);
                    return new NegateFilter(filter);
                }),
                new GrammarSequence<>(List.of(TokenType.START_SUB_EXPRESSION, EXPR, TokenType.END_SUB_EXPRESSION), forward(1))
        ));

        return new Grammar<>(
                TokenType.class,
                List.of(EXPR, OR_EXPR, OR_EXPR2, AND_EXPR, AND_EXPR2, PRIMARY_EXPR),
                EXPR);
    }

    private static Function<List<?>, ImageFilter> forward(int index) {
        return (List<?> evaluatedTokens) -> (ImageFilter) evaluatedTokens.get(index);
    }

    private static Function<List<?>, ImageFilter> biOperator(int leftIndex, int rightIndex, BiFunction<ImageFilter, ImageFilter, ImageFilter> biOperatorFilter) {
        return (List<?> evaluatedTokens) -> {
            ImageFilter leftFilter = (ImageFilter) evaluatedTokens.get(leftIndex);
            ImageFilter rightFilter = (ImageFilter) evaluatedTokens.get(rightIndex);

            if (rightFilter != null)
                return biOperatorFilter.apply(leftFilter, rightFilter);
            else
                return leftFilter;
        };
    }

    private record TagFilter(String normalizedTag) implements ImageFilter {
        @Override
        public boolean test(Image image) {
            return image.getImplicitTags().contains(normalizedTag);
        }
    }

    private record PathFilter(String normalizedPath) implements ImageFilter {
        @Override
        public boolean test(Image image) {
            return normalizePath(image.getPath().toString()).contains(normalizedPath);
        }

        static String normalizePath(String path) {
            return Utils.stripAccents(path).toLowerCase(Locale.ROOT);
        }
    }

    private record NegateFilter(ImageFilter filter) implements ImageFilter {
        @Override
        public boolean test(Image image) {
            return !filter.test(image);
        }
    }

    private record AndFilter(ImageFilter leftFilter, ImageFilter rightFilter) implements ImageFilter {
        @Override
        public boolean test(Image image) {
            return leftFilter.test(image) && rightFilter.test(image);
        }
    }

    private record OrFilter(ImageFilter leftFilter, ImageFilter rightFilter) implements ImageFilter {
        @Override
        public boolean test(Image image) {
            return leftFilter.test(image) || rightFilter.test(image);
        }
    }
}
