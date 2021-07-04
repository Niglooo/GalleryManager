package nigloo.gallerymanager.model;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.annotations.JsonAdapter;

import javafx.scene.paint.Color;
import nigloo.tool.gson.javafx.ColorTypeAdapter;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class Tag
{
	public static final Set<Character> ALLOWED_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-()!"
			.chars().mapToObj(c -> (char) c).collect(Collectors.toUnmodifiableSet());

	private String value;
	private TagReference parent;
	@JsonAdapter(ColorTypeAdapter.class)
	private Color color;
	
	@Inject
	private transient Gallery gallery;
	
	// Used by deserializer
	private Tag()
	{
		Injector.init(this);
	}
	
	Tag(String value)
	{
		this();
		this.value = value;
		
		Optional<Character> invalidChar = value.chars().mapToObj(c -> (char)c).filter(c -> !ALLOWED_CHARS.contains(c)).findFirst();
		if (invalidChar.isPresent())
			throw new IllegalArgumentException("The character '"+invalidChar.get()+"' is not allowed in tags. Got : \""+value+"\"");
	}


	public String getValue()
	{
		return value;
	}

	public Tag getParent()
	{
		return parent == null ? null : parent.getTag();
	}

	public void setParent(Tag parent)
	{
		if (parent == null)
		{
			this.parent = null;
			return;
		}
		
		// Check for cycle
		ArrayList<String> cycle = new ArrayList<>();
		cycle.add(this.getValue());
		
		Tag t = parent;
		while (t != null)
		{
			cycle.add(t.getValue());
			
			if (t == this)
				throw new IllegalArgumentException("Cannot set " + parent.getValue() + " as parent of "
						+ this.getValue() + " as that would create the cycle "
						+ cycle.stream().collect(Collectors.joining(" -> ", "[", "]")));
			
			t = t.getParent();
		}
		
		this.parent = new TagReference(parent);
	}
	
	public Color getColor()
	{
		if (color == null && parent != null)
			return parent.getTag().getColor();
		
		return color;
	}

	public void setColor(Color color)
	{
		this.color = color;
	}

	public static boolean isCharacterAllowed(char c)
	{
		return ALLOWED_CHARS.contains(c);
	}
	
	public static boolean isValideTag(CharSequence tagValue)
	{
		return tagValue.chars().mapToObj(c -> (char)c).filter(c -> !ALLOWED_CHARS.contains(c)).findFirst().isEmpty();
	}
}
