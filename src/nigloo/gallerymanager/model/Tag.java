package nigloo.gallerymanager.model;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.gson.annotations.JsonAdapter;
import javafx.scene.paint.Color;
import lombok.Getter;
import lombok.Setter;
import nigloo.tool.gson.javafx.ColorTypeAdapter;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class Tag
{
	private static final Set<Character> FORBIDDEN_CHARS = " -!+&|[]\":".chars()
	                                                            .mapToObj(c -> (char) c)
	                                                            .collect(Collectors.toUnmodifiableSet());
	@Getter
	String name;
	private HashSet<TagReference> parents;
	@Setter
	@JsonAdapter(ColorTypeAdapter.class)
	private Color color;
	
	@Inject
	private transient Gallery gallery;
	
	// Used by deserializer
	private Tag()
	{
		Injector.init(this);
	}
	
	Tag(String name)
	{
		this();
		assert name.equals(normalize(name));
		this.name = name;
		
		Optional<Character> invalidChar = name.chars()
		                                      .mapToObj(c -> (char) c)
		                                      .filter(((Predicate<Character>)Tag::isCharacterAllowed).negate())
		                                      .findFirst();
		if (invalidChar.isPresent())
			throw new IllegalArgumentException("The character '" + invalidChar.get()
			        + "' is not allowed in tags. Got : \"" + name + "\"");
	}
	
	@Override
	public String toString()
	{
		return name;
	}
	
	public Collection<Tag> getParents()
	{
		return parents == null ? Set.of() : parents.stream().map(TagReference::getTag).collect(Collectors.toUnmodifiableSet());
	}
	
	public void setParents(Collection<Tag> parents)
	{
		if (parents == null || parents.isEmpty())
		{
			this.parents = null;
			return;
		}
		
		HashSet<TagReference> potentialParents = parents.stream().map(TagReference::new).collect(Collectors.toCollection(HashSet::new));
	
		ArrayDeque<Tag> cycle = getClosestAncestorWith(potentialParents, t -> t == this);
		
		if (cycle == null)
			this.parents = potentialParents;
		else
		{
			Tag badParent = cycle.getFirst();
			cycle.addFirst(this);
			throw new IllegalArgumentException("Cannot set " + badParent.getName() + " as parent of " + this.getName()
			        + " as that would create the cycle "
			        + cycle.stream().map(Tag::getName).collect(Collectors.joining(" -> ", "[", "]")));
		}
	}
	
	private static ArrayDeque<Tag> getClosestAncestorWith(Collection<TagReference> parents, Predicate<Tag> predicate)
	{
		if (parents == null)
			return null;
		
		ArrayDeque<Tag> shortestAncestors = null;
		for (TagReference parentRef : parents)
		{
			Tag parent = parentRef.getTag();
			if (predicate.test(parent))
			{
				shortestAncestors = new ArrayDeque<>();
				shortestAncestors.add(parent);
				return shortestAncestors;
			}
			
			ArrayDeque<Tag> ancestors = getClosestAncestorWith(parent.parents, predicate);
			if (shortestAncestors == null || (ancestors != null && ancestors.size() < shortestAncestors.size()-1))
			{
				shortestAncestors = ancestors;
				
				if (shortestAncestors != null)
					shortestAncestors.addFirst(parent);
			}
		}
		
		return shortestAncestors;
	}
	
	public Color getColor()
	{
		if (color != null)
			return color;
		
		ArrayDeque<Tag> tags = getClosestAncestorWith(parents, t -> t.color != null);
		return tags == null ? null : tags.getLast().color;
	}
	
	public static boolean isCharacterAllowed(char c)
	{
		return !FORBIDDEN_CHARS.contains(c);
	}
	
	public static String normalize(String tagName)
	{
		if (tagName == null)
			return null;

		tagName = tagName
				.toLowerCase(Locale.ROOT)
				.replace(' ', '_')
				.replace('-', '_')
				.replace("!", "")
				.replace("+", "")
				.replace("&", "")
				.replace("|", "")
				.replace("[", "(")
				.replace("]", ")")
				.replace("\"", "")
				.replace(":", "")
				.trim();

		return tagName.isEmpty() ? null : tagName;
	}
}
