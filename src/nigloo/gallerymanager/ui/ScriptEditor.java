package nigloo.gallerymanager.ui;

import java.util.Optional;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.BooleanExpression;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.Parent;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.VBox;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Script;
import nigloo.gallerymanager.model.Script.AutoExecution;
import nigloo.gallerymanager.script.ScriptAPI;
import nigloo.tool.PrintString;
import nigloo.tool.Utils;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.javafx.component.dialog.AlertWithIcon;

public class ScriptEditor extends VBox
{
	private static final Logger LOGGER = LogManager.getLogger(UIController.class);
	
	@FXML
	private TextField scriptTitle;
	@FXML
	private ChoiceBox<AutoExecution> scriptAutoExecution;
	@FXML
	private TextArea scriptText;
	@FXML
	private TextArea scriptOutput;
	
	@Inject
	private Gallery gallery;
	
	private BooleanBinding changed;
	
	private final Script script;
	
	public ScriptEditor(Script script)
	{
		this.script = script;
		UIController.loadFXML(this, "script_editor.fxml");
		Injector.init(this);
		
		scriptAutoExecution.setItems(FXCollections.observableArrayList(AutoExecution.values()));
		
		scriptTitle.setText(script.getTitle());
		scriptAutoExecution.setItems(FXCollections.observableArrayList(AutoExecution.values()));
		scriptAutoExecution.setValue(script.getAutoExecution());
		scriptText.setText(script.getText());
		
		changed = new BooleanBinding()
		{
			{
				bind(scriptTitle.textProperty(), scriptAutoExecution.valueProperty(), scriptText.textProperty());
			}
			
			@Override
			protected boolean computeValue()
			{
				return !scriptTitle.getText().equals(script.getTitle()) ||
				       scriptAutoExecution.getValue() != script.getAutoExecution() ||
				       !scriptText.getText().equals(script.getText());
			}
		};
	}

	public StringProperty scriptTitleProperty()
	{
		return scriptTitle.textProperty();
	}
	
	public ObjectProperty<AutoExecution> scriptAutoExecutionProperty()
	{
		return scriptAutoExecution.valueProperty();
	}
	
	public StringProperty scriptTextProperty()
	{
		return scriptText.textProperty();
	}
	
	public BooleanExpression changedProperty()
	{
		return changed;
	}

	public Script getScript()
	{
		return script;
	}

	@FXML
	public void saveScript()
	{
		script.setTitle(scriptTitle.getText());
		script.setAutoExecution(scriptAutoExecution.getValue());
		script.setText(scriptText.getText());
		
		if (changed.get())
			changed.invalidate();
	}
	
	@FXML
	public void reloadScript()
	{
		scriptTitle.setText(script.getTitle());
		scriptAutoExecution.setValue(script.getAutoExecution());
		scriptText.setText(script.getText());
		
		if (changed.get())
			changed.invalidate();
	}
	
	@FXML
	public void deleteScript()
	{
		AlertWithIcon warningPopup = new AlertWithIcon(AlertType.WARNING);
		warningPopup.setTitle("Delete script");
		warningPopup.setHeaderText("Delete script \"" + scriptTitle.getText() + "\"?");
		warningPopup.setContentText("This action cannot be undone!");
		warningPopup.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
		warningPopup.setDefaultButton(ButtonType.NO);
		
		Optional<ButtonType> button = warningPopup.showAndWait();
		if (button.isEmpty() || button.get() != ButtonType.YES)
			return;
		
		gallery.deleteScript(script);
		
		TabPane tabPane = (TabPane) getParent().getParent();
		Tab tab = tabPane.getTabs().stream().filter(t -> t.getContent() == this).findAny().get();
		tabPane.getTabs().remove(tab);
	}
	
	@FXML
	public void runScript()
	{
		if (Utils.isBlank(scriptText.getText()))
			return;
		
		PrintString output = new PrintString();
		//TODO run script async
		try {
			System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
			ScriptEngine engine = new ScriptEngineManager().getEngineByExtension("js");
			
			LOGGER.trace("getEngineName: " + engine.getFactory().getEngineName());
			LOGGER.trace("getEngineVersion: " + engine.getFactory().getEngineVersion());
			LOGGER.trace("getLanguageName: " + engine.getFactory().getLanguageName());
			LOGGER.trace("getLanguageVersion: " + engine.getFactory().getLanguageVersion());
			
			engine.getContext().setWriter(output);
			engine.getContext().setErrorWriter(output);
			engine.getContext().setAttribute("polyglot.js.allowAllAccess", true, ScriptContext.ENGINE_SCOPE);
			
			engine.getContext().setAttribute("api", new ScriptAPI(output), ScriptContext.ENGINE_SCOPE);
			
			//afterGalleryLoadScriptOutput.setText("Executing script...");
			long begin = System.currentTimeMillis();
			engine.eval(scriptText.getText());
			long end = System.currentTimeMillis();
			output.println("Finished in "+(end-begin)+"ms");
		}
		catch (Exception e) {
			e.printStackTrace(output);
		}
		
		scriptOutput.setText(output.toString());
	}
}
