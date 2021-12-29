package nigloo.gallerymanager.model;

import java.util.List;

import nigloo.gallerymanager.autodownloader.Downloader;

public class Artist
{
	private TagReference tag;
	private String name;
	
	private List<Downloader> autodownloaders;
	
	public Artist()
	{
	}
	
	public Tag getTag()
	{
		return tag.getTag();
	}
	
	public void setTag(Tag tag)
	{
		this.tag = new TagReference(tag);
	}
	
	public String getName()
	{
		return name;
	}
	
	public void setName(String name)
	{
		this.name = name;
	}
	
	public List<Downloader> getAutodownloaders()
	{
		return autodownloaders;
	}
}
