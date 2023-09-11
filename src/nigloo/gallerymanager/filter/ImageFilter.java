package nigloo.gallerymanager.filter;

import nigloo.gallerymanager.model.Image;

import java.text.ParseException;
import java.util.function.Predicate;

public interface ImageFilter extends Predicate<Image> {

    char META_TAG_SEPARATOR = ':';
    char META_TAG_QUOTE = '"';

    String META_TAG_TYPE_PATH = "path";


    static ImageFilter parse(String filterExpression) throws ParseException {
        ImageFilterTokenizer tokenizer = new ImageFilterTokenizer(filterExpression);
        return ImageFilterGrammar.COMPILED_GRAMMAR.compile(tokenizer);
    }
}
