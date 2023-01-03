package nigloo.gallerymanager.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SlideShowParameters
{
	private boolean autoplay = true;
	private boolean shuffled = false;
	private boolean looped = true;
	private double autoplayDelay = 5;
	private VideoParameters videos = new VideoParameters();
	
	@Getter
	@Setter
	public static class VideoParameters
	{
		private boolean autoplay = true;
		private boolean mute = true;
		private double volume = 0d;
		
		public void copyFrom(VideoParameters other)
		{
			this.autoplay = other.autoplay;
			this.mute = other.mute;
			this.volume = other.volume;
		}
	}
}
