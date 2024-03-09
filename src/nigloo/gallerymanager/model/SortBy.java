package nigloo.gallerymanager.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lombok.Getter;
import lombok.experimental.Accessors;
import nigloo.gallerymanager.model.SortBy.HardCodedSorBy;
import nigloo.gallerymanager.model.SortBy.KeywordSorBy;
import nigloo.gallerymanager.model.SortBy.SorByReference;
import nigloo.gallerymanager.ui.FileSystemElement;
import nigloo.tool.Utils;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Getter
@Accessors(fluent = true, makeFinal = true)
public sealed abstract class SortBy implements Comparator<FileSystemElement>
		permits HardCodedSorBy, SorByReference, KeywordSorBy
{
	private static final Map<String, HardCodedSorBy> HARD_CODED_INSTANCES = new HashMap<>();
	private static Map<String, SortBy> CUSTOM_INSTANCES = null;

	public static final SortBy NAME = new HardCodedSorBy("NAME", Comparator.comparing(FileSystemElement::getPath, SortBy::compareIgnoringExtension));
	public static final SortBy DATE = new HardCodedSorBy("DATE", Comparator.comparingLong(FileSystemElement::getLastModified));
	//public static final SortBy DEILAN_ORDER = new KeywordSorBy("DEILAN_ORDER", List.of("clothed", "lingerie", "topless", "bottomless", "nude", "wet",  "lewd"));

	public static void setCustomInstances(Map<String, SortBy> customInstances) {
		CUSTOM_INSTANCES = customInstances;
	}

	public static SortBy valueOf(String name) {
		//HARD_CODED_INSTANCES.put(DEILAN_ORDER.name(), DEILAN_ORDER);
		SortBy sortBy = HARD_CODED_INSTANCES.get(name);
		if (sortBy != null)
			return sortBy;

		sortBy = CUSTOM_INSTANCES != null ? CUSTOM_INSTANCES.get(name) : null;
		if (sortBy != null)
			return sortBy;

		throw new IllegalArgumentException("Unknown sort order "+name);
	}

	String name;

	protected SortBy(String name) {
		this.name = name;
	}

	static final class HardCodedSorBy extends SortBy {
		private final Comparator<FileSystemElement> comparator;

        private HardCodedSorBy(String name, Comparator<FileSystemElement> comparator) {
            super(name);
            this.comparator = comparator;
			HARD_CODED_INSTANCES.put(name, this);
        }

        @Override
		public int compare(FileSystemElement o1, FileSystemElement o2) {
			return comparator.compare(o1, o2);
		}
	}

	static final class SorByReference extends SortBy {
		private SortBy sortBy = null;

		private SorByReference(String name) {
			super(name);
		}

		@Override
		public int compare(FileSystemElement o1, FileSystemElement o2) {
			if (sortBy == null) {
				sortBy = valueOf(name);
			}

			return sortBy.compare(o1, o2);
		}
	}

	static final class KeywordSorBy extends SortBy {
		private final List<String> keywords;

		private KeywordSorBy(String name, List<String> keywords) {
			super(name);
			this.keywords = keywords;
		}

		@Override
		public int compare(FileSystemElement e1, FileSystemElement e2) {

			Path p1 = e1.getPath();
			Path p2 = e2.getPath();

			String filename1 = p1.getFileName().toString();
			String filename2 = p2.getFileName().toString();

			int pos1 = 0;
			for (String keyword : keywords) {
				if (filename1.toLowerCase(Locale.ROOT).contains(keyword))
					break;
				pos1++;
			}
			int pos2 = 0;
			for (String keyword : keywords) {
				if (filename2.toLowerCase(Locale.ROOT).contains(keyword))
					break;
				pos2++;
			}

			if (pos1 == pos2)
				return compareIgnoringExtension(p1, p2);
			else
				return pos1 - pos2;
		}
	}

	private static int compareIgnoringExtension(Path p1, Path p2)
	{
		String filename1 = p1.getFileName().toString();
		int posExt1 = filename1.lastIndexOf('.');
		if (posExt1 >= 0)
		{
			String filename2 = p2.getFileName().toString();
			int posExt2 = filename2.lastIndexOf('.');
			if (posExt2 >= 0)
			{
				String path1 = p1.toString();
				path1 = path1.substring(0, path1.lastIndexOf('.'));
				
				String path2 = p2.toString();
				path2 = path2.substring(0, path2.lastIndexOf('.'));
				
				return Utils.NATURAL_ORDER.compare(path1, path2);
			}
		}
		
		return Utils.NATURAL_ORDER.compare(p1.toString(), p2.toString());
	}

	public static class SortByReferenceTypeAdapter extends TypeAdapter<SortBy>
	{
		@Override
		public void write(JsonWriter out, SortBy sortBy) throws IOException
		{
			if (sortBy == null)
				out.nullValue();
			else
				out.value(sortBy.name);
		}

		@Override
		public SortBy read(JsonReader in) throws IOException
		{
			if (in.peek() == JsonToken.NULL)
			{
				in.nextNull();
				return null;
			}

			String name = in.nextString();
			return new SorByReference(name);
		}
	}

	static class CustomSortByMapSerializer
			implements JsonSerializer<HashMap<String, SortBy>>, JsonDeserializer<HashMap<String, SortBy>>
	{
		private static final String TYPE_FIELD = "type";
		private static final Map<String, Class<? extends SortBy>> TYPE_TO_CLASS = new HashMap<>();
		private static final Map<Class<? extends SortBy>, String> CLASS_TO_TYPE = new HashMap<>();
		static {
			TYPE_TO_CLASS.put("KEYWORD", KeywordSorBy.class);

			TYPE_TO_CLASS.forEach((type, klass) -> CLASS_TO_TYPE.put(klass, type));
		}

		@Override
		public JsonElement serialize(HashMap<String, SortBy> map, Type typeOfSrc, JsonSerializationContext context)
		{
			if (map == null)
				return context.serialize(Map.of());

			JsonObject serialisedMap = context.serialize(map).getAsJsonObject();

			List<String> names = List.copyOf(map.keySet());
			for (String name : names) {
				SortBy sortBy = map.get(name);
				Class<? extends SortBy> klass = sortBy.getClass();
				String type = CLASS_TO_TYPE.get(klass);
				JsonObject serialisedSortBy = serialisedMap.getAsJsonObject(name);

				JsonObject targetSerialisedSortBy = new JsonObject();
				targetSerialisedSortBy.addProperty(TYPE_FIELD, type);
				serialisedSortBy.asMap().forEach(targetSerialisedSortBy::add);
				targetSerialisedSortBy.remove("name");

				serialisedMap.add(name, targetSerialisedSortBy);
			}

			return serialisedMap;
		}

		@Override
		public HashMap<String, SortBy> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
				throws JsonParseException
		{
			HashMap<String, SortBy> map = new HashMap<>();
			if (json == null || json.isJsonNull()) {
				return map;
			}

			JsonObject serialisedMap = json.getAsJsonObject();

			List<String> names = List.copyOf(serialisedMap.keySet());
			for (String name : names) {
				JsonObject serialisedSortBy = serialisedMap.getAsJsonObject(name);
				String type = serialisedSortBy.getAsJsonPrimitive(TYPE_FIELD).getAsString();
				Class<? extends SortBy> klass = TYPE_TO_CLASS.get(type);

				SortBy sortBy = context.deserialize(serialisedSortBy, klass);
				sortBy.name = name;

				map.put(name, sortBy);
			}

			return map;
		}
	}
}
