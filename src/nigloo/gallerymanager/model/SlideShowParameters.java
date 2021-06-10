package nigloo.gallerymanager.model;

public class SlideShowParameters
{
	private boolean shuffled = false;
	private boolean looped = true;
	
	public boolean isShuffled()
	{
		return shuffled;
	}
	
	public void setShuffled(boolean shuffled)
	{
		this.shuffled = shuffled;
	}
	
	public boolean isLooped()
	{
		return looped;
	}
	
	public void setLooped(boolean looped)
	{
		this.looped = looped;
	}
}
