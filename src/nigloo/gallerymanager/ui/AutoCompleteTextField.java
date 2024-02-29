package nigloo.gallerymanager.ui;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.DoublePropertyBase;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import javafx.util.Duration;
import lombok.Getter;

public class AutoCompleteTextField extends TextField
{
	private static final int SUGGESTION_DELAY = 500;
	
	@Getter
	private AutoCompletionBehavior autoCompletionBehavior;
	private final DoubleProperty resultMaxHeight;
	private final SuggestionsPopup entriesPopup;
	
	private final Timeline delayedShowSuggestion;
	
	private final ChangeListener<Number> caretPositionChangeListener;
	private final ChangeListener<Boolean> focusedChangeListener;
	private final EventHandler<KeyEvent> keyTypedHandler;
	
	public AutoCompleteTextField()
	{
		this.autoCompletionBehavior = (field, searchText) -> List.of();
		this.resultMaxHeight = new DoublePropertyBase(300) {
			@Override public String getName() {
				return "resultMaxHeight";
			}
			@Override public Object getBean() {
				return AutoCompleteTextField.this;
			}
		};
		this.entriesPopup = new SuggestionsPopup();
		
		delayedShowSuggestion = new Timeline(new KeyFrame(Duration.millis(SUGGESTION_DELAY),
		                                                  event -> showSuggestions(false)));
		
		caretPositionChangeListener = (observable, oldValue, newValue) ->
		{
			String searchText = autoCompletionBehavior.getSearchText(this);
			if (searchText == null || searchText.isBlank())
				entriesPopup.hide();
		};
		focusedChangeListener = (observableValue, oldValue, newValue) -> entriesPopup.hide();
		keyTypedHandler = event ->
		{
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
	
	public void setAutoCompletionBehavior(AutoCompletionBehavior autoCompletionBehavior)
	{
		this.autoCompletionBehavior = Objects.requireNonNull(autoCompletionBehavior, "autoCompletionBehavior");
	}
	
	public final DoubleProperty resultMaxHeightProperty()
	{
		return resultMaxHeight;
	}
	
	public final double getResultMaxHeight()
	{
		return resultMaxHeight.get();
	}

	public final void setResultMaxHeight(int resultMaxHeight)
	{
		this.resultMaxHeight.set(resultMaxHeight);
	}

	private void showSuggestions(boolean showEvenIfEmptySearchText)
	{
		Platform.runLater(() ->
		{
			String searchText = autoCompletionBehavior.getSearchText(this);
			Collection<String> suggestions = autoCompletionBehavior.getSuggestions(this, searchText);
			
			if ((searchText == null || searchText.isEmpty()) && !showEvenIfEmptySearchText)
			{
				// Don't show suggestions. Don't hide any visible one either (from ctrl+space)
			}
			else if (suggestions != null && !suggestions.isEmpty())
			{
				entriesPopup.showSearchResult(suggestions, searchText);
			}
			else
			{
				entriesPopup.hide();
			}
		});
	}
	
	private class SuggestionsPopup extends Popup
	{
		private final ObservableList<Node> items;
		
		public SuggestionsPopup()
		{
			VBox vBox = new VBox();
			vBox.setFillWidth(true);
			vBox.getStyleClass().add("suggestions-list");
			items = vBox.getChildren();
			
			ScrollPane scrollPane = new ScrollPane(vBox);
			scrollPane.setVbarPolicy(ScrollBarPolicy.ALWAYS);
			scrollPane.setHbarPolicy(ScrollBarPolicy.NEVER);
			scrollPane.maxHeightProperty().bind(resultMaxHeight);
			getContent().add(scrollPane);
		}
		
		public void showSearchResult(Collection<String> searchResult, String searchText)
		{
			final AutoCompleteTextField field = AutoCompleteTextField.this;
			items.clear();
			
			for (String result : searchResult)
			{
				// TextFlow to highlight found subtext in suggestions
				TextFlow textFlow = buildTextFlow(result, searchText);
				
				Hyperlink hl = new Hyperlink();
				hl.getStyleClass().add("suggestion-item");// customize "focused" effet in CSS
				hl.setGraphic(textFlow);
				hl.setMaxWidth(Double.POSITIVE_INFINITY);
				//FIXME Somehow the USE_COMPUTED_SIZE of prefHeight is fucked up so we set it to 0
				hl.setPrefHeight(0);
				// if any suggestion is select set it into text and close popup
				hl.setOnAction(actionEvent ->
				{
					enableListener(false);
					autoCompletionBehavior.onSuggestionSelected(field, result);
					enableListener(true);
					entriesPopup.hide();
				});
				hl.setOnMouseMoved(e -> {
					if (!hl.isFocused() && hl.localToScene(hl.getBoundsInLocal()).contains(e.getSceneX(), e.getSceneY())) {
						hl.requestFocus();
					}
				});
				
				items.add(hl);
			}
			
			Bounds bounds = field.localToScreen(field.getBoundsInLocal());
			super.show(field, bounds.getMinX(), bounds.getMaxY());
		}
	}
	
	/**
	 * Build TextFlow with selected text. Return "case" dependent.
	 * 
	 * @param text       - string with text
	 * @param searchText - string to select in text
	 * @return - TextFlow
	 */
	private static TextFlow buildTextFlow(String text, String searchText)
	{
		TextFlow textFlow = new TextFlow(getColoredTexts(text,
		                                                 text.toLowerCase(Locale.ROOT),
		                                                 0,
		                                                 searchText == null ? ""
		                                                         : searchText.toLowerCase(Locale.ROOT)).toArray(Text[]::new));
		return textFlow;
	}

	private static LinkedList<Text> getColoredTexts(String text,
	                                                String lowerCaseText,
	                                                int offset,
	                                                String lowerCaseSearchText)
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
