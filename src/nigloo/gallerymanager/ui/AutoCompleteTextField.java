package nigloo.gallerymanager.ui;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

public class AutoCompleteTextField extends TextField
{
	private static final int SUGGESTION_DELAY = 500;
	
	private AutoCompletionBehavior autoCompletionBehavior;
	private ContextMenu entriesPopup;

	private final Timeline delayedShowSuggestion;
	
	private final ChangeListener<Number> caretPositionChangeListener;
	private final ChangeListener<Boolean> focusedChangeListener;
	private final EventHandler<KeyEvent> keyTypedHandler;
	
	public AutoCompleteTextField()
	{
		this.entriesPopup = new ContextMenu();
		this.autoCompletionBehavior = (field, searchText) -> List.of();
		
		delayedShowSuggestion = new Timeline(new KeyFrame(Duration.millis(SUGGESTION_DELAY), event -> showSuggestions(false)));
		
		caretPositionChangeListener = (observable, oldValue, newValue) -> 
		{
			String searchText = autoCompletionBehavior.getSearchText(this);
			if (searchText == null || searchText.isBlank())
				entriesPopup.hide();
		};
		focusedChangeListener = (observableValue, oldValue, newValue) -> entriesPopup.hide();
		keyTypedHandler = event -> {
			String chars = event.getCharacter();
			
			if (chars.equals(" ") && event.isControlDown())
				showSuggestions(true);
			else if (!chars.contains("\r") && !chars.contains("\n") && !event.isControlDown())
				delayedShowSuggestion.playFromStart();
		};
		
		enableListener(true);
	}

	private void enableListener(boolean enable)
	{
		if (enable)
		{
			caretPositionProperty().addListener(caretPositionChangeListener);
			focusedProperty().addListener(focusedChangeListener);
			setOnKeyTyped(keyTypedHandler);
		}
		else
		{
			caretPositionProperty().removeListener(caretPositionChangeListener);
			focusedProperty().removeListener(focusedChangeListener);
			setOnKeyTyped(null);
		}
	}
	

	public AutoCompletionBehavior getAutoCompletionBehavior() {
		return autoCompletionBehavior;
	}

	public void setAutoCompletionBehavior(AutoCompletionBehavior autoCompletionBehavior) {
		this.autoCompletionBehavior = Objects.requireNonNull(autoCompletionBehavior, "autoCompletionBehavior");
	}
	
	
	public void showSuggestions(boolean showEvenIfEmptySearchText)
	{
		Platform.runLater(() ->
		{
			String searchText = autoCompletionBehavior.getSearchText(this);
			Collection<String> suggestions = autoCompletionBehavior.getSuggestions(this, searchText);
			
			if ((searchText == null || searchText.isEmpty()) && !showEvenIfEmptySearchText)
			{
				// Don't show suggestions don't hide any visible one either (from ctrl+space)
			}
			else if (suggestions != null && !suggestions.isEmpty())
			{
				populatePopup(suggestions, searchText);
				entriesPopup.show(this, Side.BOTTOM, 0, 0);
			}
			else
			{
				entriesPopup.hide();
			}
		});
	}

	/**
	 * Populate the entry set with the given search results. Display is limited to
	 * 10 entries, for performance.
	 * 
	 * @param searchResult The set of matching strings.
	 */
	private void populatePopup(Collection<String> searchResult, String searchText)
	{
		// List of "suggestions"
		List<CustomMenuItem> menuItems = new LinkedList<>();
		
		for (String result : searchResult)
		{
			// label with graphic (text flow) to highlight found subtext in suggestions
			Label entryLabel = new Label();
			entryLabel.setGraphic(buildTextFlow(result, searchText)); // Somehow this change the prefHeight...
			entryLabel.setPrefHeight(10);
			CustomMenuItem item = new CustomMenuItem(entryLabel, true);
			menuItems.add(item);

			// if any suggestion is select set it into text and close popup
			item.setOnAction(actionEvent ->
			{
				enableListener(false);
				autoCompletionBehavior.onSuggestionSelected(this, result);
				enableListener(true);
				entriesPopup.hide();
			});
		}

		// "Refresh" context menu
		entriesPopup.getItems().setAll(menuItems);
	}

	/**
	 * Build TextFlow with selected text. Return "case" dependent.
	 * 
	 * @param text   - string with text
	 * @param searchText - string to select in text
	 * @return - TextFlow
	 */
	public static TextFlow buildTextFlow(String text, String searchText)
	{
		return new TextFlow(getColoredTexts(text, text.toLowerCase(Locale.ROOT), 0, searchText == null ? null : searchText.toLowerCase(Locale.ROOT)).toArray(new Text[0]));
	}
	
	private static LinkedList<Text> getColoredTexts(String text, String lowerCaseText, int offset, String lowerCaseSearchText)
	{
		LinkedList<Text> result;
		
		if (lowerCaseSearchText.isEmpty())
		{
			result = new LinkedList<>();
			result.add(new Text(text));
		}
		else
		{
			int idx = lowerCaseText.indexOf(lowerCaseSearchText, offset);
			if (idx < 0)
			{
				result = new LinkedList<>();
				if (offset < text.length())
					result.add(new Text(text.substring(offset)));
			}
			else
			{
				int idxEnd = idx + lowerCaseSearchText.length();
				
				result = getColoredTexts(text, lowerCaseText, idxEnd, lowerCaseSearchText);
				
				Text coloredText = new Text(text.substring(idx, idxEnd));
				coloredText.getStyleClass().add("highlight");
				result.addFirst(coloredText);
				
				if (idx > offset)
					result.addFirst(new Text(text.substring(offset, idx)));
			}
		}
		
		return result;
	}
	
	public interface AutoCompletionBehavior
	{
		default String getSearchText(AutoCompleteTextField field)
		{
			return field.getText();
		}
		
		Collection<String> getSuggestions(AutoCompleteTextField field, String searchText);
		
		default void onSuggestionSelected(AutoCompleteTextField field, String suggestion)
		{
			field.setText(suggestion);
			field.positionCaret(suggestion.length());
		}
	}
}
