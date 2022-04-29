package nigloo.gallerymanager.model;

import nigloo.tool.Utils;

public class Script
{
	public enum AutoExecution
	{
		ON_APP_START, ON_APP_STOP, NONE
	}
	
	private String title = "";
	private AutoExecution autoExecution = AutoExecution.NONE;
	private String text = "";
	
	Script()
	{
	}
	
	public String getTitle()
	{
		return title;
	}
	
	public void setTitle(String title)
	{
		this.title = Utils.coalesce(title, "");
	}
	
	public AutoExecution getAutoExecution()
	{
		return autoExecution;
	}
	
	public void setAutoExecution(AutoExecution autoExecution)
	{
		this.autoExecution = Utils.coalesce(autoExecution, AutoExecution.NONE);
	}
	
	public String getText()
	{
		return text;
	}
	
	public void setText(String text)
	{
		this.text = Utils.coalesce(text, "");
	}
}
