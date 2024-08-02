package nigloo.gallerymanager.ui.dialog;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.RowConstraints;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.model.Tag;
import nigloo.gallerymanager.ui.AutoCompleteTextField;
import nigloo.gallerymanager.ui.UIController;
import nigloo.gallerymanager.ui.util.AutoCompleteTag;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.javafx.FXUtils;

public class EditImageTagsDialog extends Stage
{
	@Inject
	private Gallery gallery;
	
	@FXML
	private AutoCompleteTextField tagNameField;
	@FXML
	private AutoCompleteTextField parentTagField;
	@FXML
	private Label messageLabel;
	@FXML
	private GridPane tagListView;
	@FXML
	private RowConstraints rowConstraint;
	
	private Collection<Image> images;
	
	private final Map<Tag, Text> tagToTextCount;
	private final Map<Tag, CheckBox> tagToCheckBox;
	
	public EditImageTagsDialog(Window owner)
	{
		super(StageStyle.UTILITY);
		initOwner(owner);
		initModality(Modality.APPLICATION_MODAL);
		
		UIController.loadFXML(this, "edit_image_tags_popup.fxml");
		Injector.init(this);
		
		getScene().getStylesheets().add(UIController.STYLESHEET_DEFAULT);

		AutoCompleteTag.singleTag(gallery, tagNameField);
		AutoCompleteTag.multipleTags(gallery, parentTagField);
		
		tagNameField.textProperty().addListener((obs, oldValue, newValue) ->
		{
			Tag tag = gallery.findTag(newValue);
			if (tag != null)
				parentTagField.setText(tag.getParents().stream().map(Tag::getName).collect(Collectors.joining(" ")));
		});
		
		hideMessage();
		
		tagToTextCount = new HashMap<>();
		tagToCheckBox = new HashMap<>();
		
		setOnShowing(e -> updateTags());
	}
	

	
	private void hideMessage()
	{
		messageLabel.setManaged(false);
	}
	
	private void showErrorMessage(String message)
	{
		messageLabel.setText(message);
		messageLabel.getStyleClass().setAll("error-message");
		messageLabel.setManaged(true);
	}
	
	private void showInfoMessage(String message)
	{
		messageLabel.setText(message);
		messageLabel.getStyleClass().setAll("info-message");
		messageLabel.setManaged(true);
	}
	
	private void updateTags()
	{
		tagListView.getChildren().clear();
		tagListView.getRowConstraints().clear();
		tagToCheckBox.clear();
		
		images.stream()
		      .flatMap(image -> image.getTags().stream())
		      .collect(Collectors.groupingBy(tag -> tag, Collectors.counting()))
		      .entrySet()
		      .stream()
		      .sorted(Comparator.<Entry<Tag,Long>,Long>comparing(Entry::getValue, Comparator.reverseOrder()).thenComparing(e -> e.getKey().getName()))
		      .forEachOrdered(entry -> addTag(entry.getKey(), entry.getValue()));
	}
	
	private void addTag(Tag tag, long count)
	{
		if (tagToCheckBox.containsKey(tag))
			return;
		
		Color tagColor = tag.getColor();
		
		Text tagText = new Text(tag.getName());
		tagText.getStyleClass().add("tag");
		if (tagColor != null)
			tagText.setStyle("-fx-fill: " + FXUtils.toRGBA(tagColor) + ";");
		
		Text tagCountText = new Text(String.valueOf(count));
		tagCountText.getStyleClass().add("tag-count");
		
		TextFlow tagEntry = new TextFlow(tagText, new Text(" "), tagCountText);
		tagEntry.getStyleClass().add("tag-entry");
		
		CheckBox checkBox = new CheckBox();
		boolean interminate = count > 0 && count != images.size();
		checkBox.setSelected(count == images.size());
		checkBox.setAllowIndeterminate(interminate);
		checkBox.setIndeterminate(interminate);
		
		tagToTextCount.put(tag, tagCountText);
		tagToCheckBox.put(tag, checkBox);
		
		int row = tagListView.getRowCount();
		tagListView.add(tagEntry, 0, row);
		tagListView.add(checkBox, 1, row);
		tagListView.getRowConstraints().add(rowConstraint);
	}
	
	public void setImages(Collection<Image> images)
	{
		this.images = images == null ? List.of() : images;
	}
	
	@FXML
	protected void addTag()
	{
		if (tagNameField.getText().isEmpty())
		{
			showErrorMessage("Tag is empty");
			return;
		}
		
		addTag(gallery.getTag(tagNameField.getText()), images.size());
	}
	
	@FXML
	protected void saveParent()
	{
		if (tagNameField.getText().isEmpty())
		{
			showErrorMessage("Tag is empty");
			return;
		}
		
		List<Tag> parents = Arrays.stream(parentTagField.getText().split(" ")).filter(s -> !s.isEmpty()).distinct().map(s -> gallery.getTag(s)).toList();
		
		Tag tag = gallery.getTag(tagNameField.getText());
		
		try
		{
			tag.setParents(parents);
			showInfoMessage("Set " + parents + " as parents of " + tag.getName());
		}
		catch (Exception e)
		{
			showErrorMessage(e.getMessage());
		}
	}
	
	@FXML
	protected void saveTags()
	{
		for (Entry<Tag, CheckBox> entry : tagToCheckBox.entrySet())
		{
			Tag tag = entry.getKey();
			CheckBox checkBox = entry.getValue();
			
			if (checkBox.isIndeterminate())
				continue;
			
			checkBox.setAllowIndeterminate(false);
			
			int newCount;
			
			if (checkBox.isSelected())
			{
				for (Image image : images)
					image.addTag(tag);
				newCount = images.size();
			}
			else
			{
				for (Image image : images)
					image.removeTag(tag);
				newCount = 0;
			}
			
			tagToTextCount.get(tag).setText(String.valueOf(newCount));
		}
		
		showInfoMessage("Tags updated");
	}
}
