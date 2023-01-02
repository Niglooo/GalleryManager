package nigloo.gallerymanager.model;

import lombok.Getter;
import nigloo.tool.Utils;

public class Script
{
	public enum AutoExecution
	{
		ON_APP_START, ON_APP_STOP, NONE
	}
	
	@Getter
	private String title = "";
	@Getter
	private AutoExecution autoExecution = AutoExecution.NONE;
	@Getter
	private String text = "";
	
	Script()
	{
	}
	
	public void setTitle(String title)
	{
		this.title = Utils.coalesce(title, "");
	}
	
	public void setAutoExecution(AutoExecution autoExecution)
	{
		this.autoExecution = Utils.coalesce(autoExecution, AutoExecution.NONE);
	}
	
	public void setText(String text)
	{
		this.text = Utils.coalesce(text, "");
	}
}
