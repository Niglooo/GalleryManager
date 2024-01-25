package nigloo.gallerymanager.ui;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.BooleanExpression;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import nigloo.gallerymanager.AsyncPools;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Script;
import nigloo.gallerymanager.model.Script.AutoExecution;
import nigloo.gallerymanager.script.ScriptAPI;
import nigloo.tool.Utils;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.javafx.component.dialog.AlertWithIcon;

public class ScriptEditor extends SplitPane
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

	private final BooleanProperty running;
	
	private final Script script;
	
	public ScriptEditor(Script script)
	{
		this.script = script;
		// Need to be initialized early because loading the FXML will use the initial value.
		running = new SimpleBooleanProperty(this, "running", false);
		UIController.loadFXML(this, "script_editor.fxml");
		Injector.init(this);
		
		scriptTitle.setText(script.getTitle());
		scriptAutoExecution.setItems(FXCollections.observableArrayList(AutoExecution.values()));
		scriptAutoExecution.setValue(script.getAutoExecution());
		scriptText.setText(script.getText());
		
		changed = new ScriptEditorChangedProperty();
		
		scriptOutput.textProperty().addListener((obs, oldValue, newValue) -> scriptOutput.setScrollTop(Double.MAX_VALUE));
	}

	public BooleanProperty runningProperty() {
		return running;
	}

	public boolean isRunning() {
		return runningProperty().get();
	}
	
	private class ScriptEditorChangedProperty extends BooleanBinding
	{
		public ScriptEditorChangedProperty()
		{
			bind(scriptTitle.textProperty(), scriptAutoExecution.valueProperty(), scriptText.textProperty());
		}
		
		@Override
		protected boolean computeValue()
		{
			return !scriptTitle.getText().equals(script.getTitle())
			        || scriptAutoExecution.getValue() != script.getAutoExecution()
			        || !scriptText.getText().equals(script.getText());
		}
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
		scriptOutput.setText("");
		
		if (Utils.isBlank(scriptText.getText()))
			return;

		running.set(true);
		CompletableFuture
				.runAsync(this::doRunScript, AsyncPools.SCRIPT_EXECUTION)
				.thenRunAsync(() -> running.set(false), AsyncPools.FX_APPLICATION);
	}
	
	private void doRunScript()
	{
		PrintWriter output = new PrintWriter(new ScriptOutputWriter());
		
		try (ScriptAPI scriptApi = new ScriptAPI(output))
		{
			System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
			ScriptEngine engine = new ScriptEngineManager().getEngineByExtension("js");
			
			LOGGER.trace("getEngineName: " + engine.getFactory().getEngineName());
			LOGGER.trace("getEngineVersion: " + engine.getFactory().getEngineVersion());
			LOGGER.trace("getLanguageName: " + engine.getFactory().getLanguageName());
			LOGGER.trace("getLanguageVersion: " + engine.getFactory().getLanguageVersion());
			
			engine.getContext().setWriter(output);
			engine.getContext().setErrorWriter(output);
			engine.getContext().setAttribute("polyglot.js.allowAllAccess", true, ScriptContext.ENGINE_SCOPE);
			
			engine.getContext().setAttribute("api", scriptApi, ScriptContext.ENGINE_SCOPE);
			
			output.println("Executing script...");
			output.println();
			long begin = System.currentTimeMillis();
			engine.eval(scriptText.getText());
			long end = System.currentTimeMillis();
			output.println();
			output.println("Finished in " + (end - begin) + "ms");
		}
		catch (Exception e)
		{
			e.printStackTrace(output);
		}
	}
	
	private class ScriptOutputWriter extends Writer
	{
		private static final long FLUSH_INTERVAL = 500;
		
		private final Thread flusherDaemon;
		
		private final StringBuilder buffer = new StringBuilder();
		private volatile boolean closed = false;
		private volatile long lastFlush = 0;
		private volatile boolean needFlush = false;
		
		public ScriptOutputWriter()
		{
			flusherDaemon = new Thread(this::runAutoFlush, "script-output-flusher-thread");
			flusherDaemon.setDaemon(true);
			flusherDaemon.start();
		}
		
		void runAutoFlush()
		{
			while (!closed)
			{
				long sinceLastFlush = System.currentTimeMillis() - lastFlush;
				long timeToWait;
				
				if (!needFlush)
					timeToWait = FLUSH_INTERVAL;
				else if (sinceLastFlush > FLUSH_INTERVAL)
				{
					flush();
					timeToWait = FLUSH_INTERVAL;
				}
				else
					timeToWait = FLUSH_INTERVAL - sinceLastFlush;
				
				try
				{
					Thread.sleep(timeToWait);
				}
				catch (InterruptedException e)
				{
					return;
				}
			}
		}
		
		@Override
		public void write(char[] cbuf, int off, int len)
		{
			synchronized (buffer)
			{
				buffer.append(cbuf, off, len);
				needFlush = true;
			}
		}
		
		@Override
		public void write(int c)
		{
			synchronized (buffer)
			{
				buffer.append((char) c);
				needFlush = true;
			}
		}
		
		@Override
		public void flush()
		{
			String out;
			synchronized (buffer)
			{
				lastFlush = System.currentTimeMillis();
				needFlush = false;
				out = buffer.toString();
				buffer.setLength(0);
			}
			
			Platform.runLater(() -> scriptOutput.appendText(out));
		}
		
		@Override
		public void close()
		{
			synchronized (buffer)
			{
				flush();
				closed = true;
				
				try
				{
					flusherDaemon.join();
				}
				catch (InterruptedException ignored)
				{
				}
			}
		}
	}
}
