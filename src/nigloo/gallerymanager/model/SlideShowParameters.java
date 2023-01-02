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
}
