package nigloo.gallerymanager.filter;

import nigloo.gallerymanager.model.Image;

import java.text.ParseException;
import java.util.function.Predicate;

public interface ImageFilter extends Predicate<Image> {

    static ImageFilter parse(String filterExpression) throws ParseException {
        ImageFilterTokenizer tokenizer = new ImageFilterTokenizer(filterExpression);
        return ImageFilterGrammar.COMPILED_GRAMMAR.compile(tokenizer);
    }
}
