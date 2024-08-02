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
import lombok.extern.log4j.Log4j2;
import nigloo.gallerymanager.model.SortBy.CustomSorBy;
import nigloo.gallerymanager.model.SortBy.HardCodedSorBy;
import nigloo.gallerymanager.model.SortBy.KeywordSorBy;
import nigloo.gallerymanager.model.SortBy.SorByReference;
import nigloo.gallerymanager.script.ScriptUtil;
import nigloo.gallerymanager.ui.FileSystemElement;
import nigloo.tool.Utils;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.swing.text.StyledEditorKit.BoldAction;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Accessors(fluent = true, makeFinal = true)
public sealed abstract class SortBy implements Comparator<FileSystemElement>
		permits HardCodedSorBy, SorByReference, KeywordSorBy, CustomSorBy
{
	private static final Map<String, HardCodedSorBy> HARD_CODED_INSTANCES = new HashMap<>();
	private static Map<String, SortBy> CUSTOM_INSTANCES = null;

	public static final SortBy NAME = new HardCodedSorBy("NAME", Comparator.comparing(FileSystemElement::getPath, SortBy::compareIgnoringExtension));
	public static final SortBy DATE = new HardCodedSorBy("DATE", Comparator.comparingLong(FileSystemElement::getLastModified));
	public static final SortBy ROCKSET = new HardCodedSorBy("ROCKSET", Comparator.comparing(FileSystemElement::getPath, SortBy::rocksetOrder));
	public static final SortBy KOUYOU = new HardCodedSorBy("KOUYOU", Comparator.comparing(FileSystemElement::getPath, SortBy::kouyouOrder));

	public static void setCustomInstances(Map<String, SortBy> customInstances) {
		CUSTOM_INSTANCES = customInstances;
	}

	public static SortBy valueOf(String name) {
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

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
			return true;
		if (!(o instanceof SortBy other))
			return false;

		return name.equals(other.name);
	}

	@Override
	public int hashCode()
	{
		return name.hashCode();
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
public static final Map<Boolean,List<Long>> times = new HashMap<>(); static{times.put(true, new ArrayList<>());times.put(false, new ArrayList<>());}
	@Log4j2
	static final class CustomSorBy extends SortBy {

		private static final ScriptEngine SCRIPT_ENGINE;

		private final String initScript;
		private final String compareScript;

		private transient Boolean initSuccess;
		private transient Bindings initialBindings;
		private  transient CompiledScript compiledCompareScript;

		static {
			ScriptEngine scriptEngine = null;
			try {
				scriptEngine = ScriptUtil.createScriptEngine();
				if (!(scriptEngine instanceof Compilable)) {
					log.warn("Script compilation not available for {}", CustomSorBy.class.getSimpleName());
				}
			} catch (Exception e) {
				log.error("CustomSorBy disabled", e);
			}

			SCRIPT_ENGINE = scriptEngine;
		}


		private CustomSorBy(String name, String initScript, String compareScript) {
			super(name);
			this.initScript = initScript;
			this.compareScript = compareScript;
		}

		private boolean checkInit() {
			if (initSuccess == null) {
				initialBindings = SCRIPT_ENGINE.createBindings();
				initialBindings.putAll(SCRIPT_ENGINE.getBindings(ScriptContext.ENGINE_SCOPE));
				initSuccess = true;
				if (initScript != null && !initScript.isBlank()) {
					try {
						SCRIPT_ENGINE.eval(initScript, initialBindings);
					}
					catch (Exception e) {
						initSuccess = false;
						log.error("Error when initializing CustomSorBy {}", name, e);
					}
				}
				if (SCRIPT_ENGINE instanceof Compilable c) {
					try {
						compiledCompareScript = c.compile(compareScript);
						log.debug("Successfully compiled compareScript for CustomSorBy {}", name);
					}
					catch (Exception e) {
						initSuccess = false;
						log.error("Error when compiling compareScript for CustomSorBy {}", name, e);
					}
				}
			}
			return initSuccess;
		}

		@Override
		public int compare(FileSystemElement e1, FileSystemElement e2) {
			if (!checkInit())
				return 0;

			try {
				Bindings bindings = SCRIPT_ENGINE.createBindings();
				bindings.putAll(initialBindings);
				bindings.put("e1", e1);
				bindings.put("e2", e2);

				boolean runComp = ThreadLocalRandom.current().nextBoolean();

				long s=System.nanoTime();
				try
				{
					if (compiledCompareScript != null && runComp)
						return (int) compiledCompareScript.eval(bindings);
					else
						return (int) SCRIPT_ENGINE.eval(compareScript, bindings);
				} finally {
					long e = System.nanoTime();
					times.get(runComp).add(e-s);
					//System.out.println((runComp?"Compiled    : ":"Not compiled: ")+(e-s)+"ns");
				}
			}
			catch (Exception e) {
				log.error("Error when comparing {} and {} using {}", e1, e2, name, e);
				return 0;
			}
		}
	}

	public static int compareIgnoringExtension(Path p1, Path p2)
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

	private static int rocksetOrder(Path p1, Path p2)
	{
		int comp = compareIgnoringExtension(p1, p2);

		for (int i = 0 ; i < p1.getNameCount() && i < p2.getNameCount() ; i++)
		{
			String n1 = p1.getName(i).toString();
			String n2 = p2.getName(i).toString();

			if (n1.equalsIgnoreCase(n2+" alt") ||
			    n2.equalsIgnoreCase(n1+" alt"))
			{
				return -comp;
			}
		}

		return comp;
	}

	private static final Pattern KOUYOU_SEC_PATTERN = Pattern.compile("sec_(\\d{6})_(\\d{2})(.*)\\.\\w+");
	private static final Pattern KOUYOU_REG_PATTERN = Pattern.compile("(\\d{8})(_.*)\\.\\w+");
	private static int kouyouOrder(Path p1, Path p2)
	{
		int comp = 0;

		for (int i = 0 ; i < p1.getNameCount() && i < p2.getNameCount() ; i++)
		{
			String n1 = p1.getName(i).toString();
			String n2 = p2.getName(i).toString();

			Matcher m;
			if ((m = KOUYOU_SEC_PATTERN.matcher(n1)).matches()) {
				n1 = m.group(1) + m.group(2) + "Z" + m.group(3);
			}
			else if ((m = KOUYOU_REG_PATTERN.matcher(n1)).matches()) {
				n1 = m.group(1) + "A" + m.group(2);
			}

			if ((m = KOUYOU_SEC_PATTERN.matcher(n2)).matches()) {
				n2 = m.group(1) + m.group(2) + "Z" + m.group(3);
			}
			else if ((m = KOUYOU_REG_PATTERN.matcher(n2)).matches()) {
				n2 = m.group(1) + "A" + m.group(2);
			}

			comp = Utils.NATURAL_ORDER.compare(n1, n2);
			if (comp != 0)
				break;
		}

		return comp;
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
			TYPE_TO_CLASS.put("CUSTOM", CustomSorBy.class);

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
