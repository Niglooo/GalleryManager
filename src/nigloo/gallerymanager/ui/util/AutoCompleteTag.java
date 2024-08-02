package nigloo.gallerymanager.ui.util;

import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextFormatter.Change;
import lombok.RequiredArgsConstructor;
import nigloo.gallerymanager.filter.ImageFilter;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Tag;
import nigloo.gallerymanager.ui.AutoCompleteTextField;
import nigloo.gallerymanager.ui.AutoCompleteTextField.AutoCompletionBehavior;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoCompleteTag
{
    public static void singleTag(Gallery gallery, AutoCompleteTextField field)
    {
        field.setAutoCompletionBehavior((f, searchText) -> autocompleteTags(gallery, searchText));
        field.setTextFormatter(tagFormatter());
    }

    public static void multipleTags(Gallery gallery, AutoCompleteTextField field)
    {
        field.setAutoCompletionBehavior(new MultiTagsAutocompleteBehavior(gallery, false));
        field.setTextFormatter(tagFormatter(' '));
    }

    public static void tagSearchExpression(Gallery gallery, AutoCompleteTextField field)
    {
        field.setAutoCompletionBehavior(new MultiTagsAutocompleteBehavior(gallery, true));
    }

    @RequiredArgsConstructor
    private static class MultiTagsAutocompleteBehavior implements AutoCompletionBehavior
    {
        private static final String PATH_PREFIX;
        private static final Pattern PATH_PATTERN;
        static
        {
            PATH_PREFIX = ImageFilter.META_TAG_TYPE_PATH + ImageFilter.META_TAG_SEPARATOR;
            String path = Pattern.quote(PATH_PREFIX);
            String q = Pattern.quote(String.valueOf(ImageFilter.META_TAG_QUOTE));
            PATH_PATTERN = Pattern.compile(".*\\b(" + path + "(" + q + "[^" + q + "]*" + q + "?|\\S*))");
        }

        private final Gallery gallery;
        private final boolean allowMetatag;

        @Override
        public String getSearchText(AutoCompleteTextField field)
        {
            int caret = field.getCaretPosition();
            String text = field.getText();

            if (allowMetatag)
            {
                Matcher m = PATH_PATTERN.matcher(text.substring(0, caret));
                if (m.matches())
                    return m.group(1);
            }

            if (caret < text.length() && Tag.isCharacterAllowed(text.charAt(caret)))
                return "";

            int idxBeginTag = findBeginSearchText(field);
            if (idxBeginTag == caret)
                return "";

            return text.substring(idxBeginTag, caret);
        };

        @Override
        public Collection<String> getSuggestions(AutoCompleteTextField field, String searchText)
        {
            if (allowMetatag)
            {
                if (searchText.startsWith(PATH_PREFIX))
                    return List.of(searchText);

                if (PATH_PREFIX.startsWith(searchText))
                {
                    ArrayList<String> suggestions = new ArrayList<>();
                    suggestions.add(PATH_PREFIX);
                    suggestions.addAll(autocompleteTags(gallery, searchText));
                    return suggestions;
                }
            }

            return autocompleteTags(gallery, searchText);
        }

        @Override
        public void onSuggestionSelected(AutoCompleteTextField field, String suggestion)
        {
            int caret = field.getCaretPosition();
            int idxBeginTag = findBeginSearchText(field);
            String text = field.getText();

            String newText = text.substring(0, idxBeginTag) + suggestion + text.substring(caret);
            field.setText(newText);
            field.positionCaret(idxBeginTag + suggestion.length());
        };

        private int findBeginSearchText(AutoCompleteTextField field)
        {
            int caret = field.getCaretPosition();
            if (caret == 0)
                return 0;

            String text = field.getText();

            if (allowMetatag)
            {
                Matcher m = PATH_PATTERN.matcher(text.substring(0, caret));
                if (m.matches())
                    return caret - m.group(1).length();
            }

            int idxBeginTag = caret;
            while (idxBeginTag > 0 && Tag.isCharacterAllowed(text.charAt(idxBeginTag - 1)))
                idxBeginTag--;

            return idxBeginTag;
        }
    }

    private static List<String> autocompleteTags(Gallery gallery, String tagSearch)
    {
        tagSearch = Tag.normalize(tagSearch);
        if (tagSearch == null)
            tagSearch = "";

        List<String> matchingTags = new ArrayList<>();
        Map<String, Integer> matchingTagPos = new HashMap<>();

        for (Tag tag : gallery.getTags())
        {
            String tagName = tag.getName();

            int pos = tagName.indexOf(tagSearch);
            if (pos >= 0)
            {
                matchingTags.add(tagName);
                matchingTagPos.put(tagName, pos);
            }
        }

        matchingTags.sort(Comparator.comparing((String tagName) -> matchingTagPos.get(tagName))
                                    .thenComparing(String.CASE_INSENSITIVE_ORDER));

        return matchingTags;
    }

    private static TextFormatter<?> tagFormatter(Character... extraChars)
    {
        Set<Character> extraCharsSet = Set.of(extraChars);

        return new TextFormatter<>(new UnaryOperator<Change>()
        {
            @Override
            public TextFormatter.Change apply(TextFormatter.Change change)
            {
                String newValue = change.getControlNewText();

                if (newValue == null || newValue.isEmpty())
                    return change;

                for (int i = 0 ; i < newValue.length() ; i++)
                {
                    char c = newValue.charAt(i);
                    if (!Tag.isCharacterAllowed(c) && !extraCharsSet.contains(c))
                        return null;
                }

                return change;
            }
        });
    }
}
