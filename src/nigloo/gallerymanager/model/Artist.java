package nigloo.gallerymanager.model;

import java.util.List;

import nigloo.gallerymanager.autodownloader.FanboxDownloader;

public class Artist {

	private String tag;
	private String name;
	
	private List<FanboxDownloader> autodownloaders;
	
	public Artist() {
		
	}

	public String getTag()
	{
		return tag;
	}
	
	public void setTag(String tag)
	{
		this.tag = tag;
	}
	
	public String getName()
	{
		return name;
	}
	
	public void setName(String name)
	{
		this.name = name;
	}
	
	public List<FanboxDownloader> getAutodownloaders()
	{
		return autodownloaders;
	}
	
	public void setAutodownloaders(List<FanboxDownloader> autodownloaders)
	{
		this.autodownloaders = autodownloaders;
	}
}
