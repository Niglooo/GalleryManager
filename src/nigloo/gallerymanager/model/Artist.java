package nigloo.gallerymanager.model;

import java.util.List;

import nigloo.gallerymanager.autodownloader.BaseDownloader;

public class Artist
{
	private TagReference tag;
	private String name;
	
	private List<BaseDownloader> autodownloaders;
	
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
	
	public List<BaseDownloader> getAutodownloaders()
	{
		return autodownloaders;
	}
}
