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
import nigloo.gallerymanager.model.SortBy.SorByReference;
import nigloo.gallerymanager.model.SortBy.HardCodedSorBy;
import nigloo.gallerymanager.script.ScriptAPI.APIFileSystemElement;
import nigloo.gallerymanager.ui.FileSystemElement;
import nigloo.tool.Utils;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

@Getter
@Accessors(fluent = true, makeFinal = true)
public sealed abstract class SortBy implements Comparator<FileSystemElement>
		permits HardCodedSorBy, CustomSorBy, SorByReference
{
	private static final Map<String, HardCodedSorBy> HARD_CODED_INSTANCES = new HashMap<>();
	private static Map<String, CustomSorBy> CUSTOM_INSTANCES = null;

	public static final SortBy NAME = new HardCodedSorBy("NAME", Comparator.comparing(FileSystemElement::getPath, SortBy::compareIgnoringExtension));
	public static final SortBy DATE = new HardCodedSorBy("DATE", Comparator.comparingLong(FileSystemElement::getLastModified));

	public static void setCustomInstances(Map<String, CustomSorBy> customInstances) {
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

	@Log4j2
	public static final class CustomSorBy extends SortBy {

		private final String className;
		private final String source;

		private transient Boolean initSuccess;
		private transient Comparator<APIFileSystemElement> compiledComparator;


		private static final String CLASS_PATH_THIS_MODULE = classPathFromClass(SortBy.class);
		private static final String CLASS_PATH_NIGLOO_TOOL = classPathFromClass(Utils.class);

		private static String classPathFromClass(Class<?> klass) {
			try {
				Path classPath = Paths.get(klass.getResource(klass.getSimpleName() + ".class").toURI());
				long nbDot = klass.getName().chars().filter(c -> c == '.').count();
				for (int i = 0; i < nbDot + 1; i++) {
					classPath = classPath.getParent();
				}
				return classPath.toString();
			}
			catch (Exception e) {
				throw new ExceptionInInitializerError(e);
			}
		}


		private CustomSorBy(String name, String className, String source) {
			super(name);
			this.className = className;
			this.source = source;
		}

		private boolean checkInit() {
			if (initSuccess == null) {
				try {
					// Save source in .java file.
					Path root = Files.createTempDirectory("java");
					Path sourceFile = root.resolve(className.replace('.', '/')+".java");
					Files.createDirectories(sourceFile.getParent());
					Files.writeString(sourceFile, source);

					// Compile source file.
					ByteArrayOutputStream error = new ByteArrayOutputStream();
					JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
					int retCode = compiler.run(null, null, error,
											   "-classpath", CLASS_PATH_THIS_MODULE+";"+CLASS_PATH_NIGLOO_TOOL,
											   sourceFile.toString());
					if (retCode != 0) {
						throw new IllegalArgumentException(error.toString(StandardCharsets.UTF_8));
					}

					// Load and instantiate compiled class.
					URLClassLoader classLoader = URLClassLoader.newInstance(new URL[]{root.toUri().toURL()});
					Class<?> cls = Class.forName(className, false, classLoader);
					SortBy.class.getModule().addExports("nigloo.gallerymanager.script", cls.getModule());
					Object instance = cls.getDeclaredConstructor().newInstance();

					if (instance instanceof Comparator<?> comp) {
                        //noinspection unchecked
                        compiledComparator = (Comparator<APIFileSystemElement>) comp;
						initSuccess = true;
					}
					else {
						throw new IllegalArgumentException(instance + " is not a Comparator<APIFileSystemElement>");
					}
				}
				catch (Exception e) {
					initSuccess = false;
					log.error("Error when initializing CustomSorBy {}", name, e);
				}
			}
			return initSuccess;
		}

		@Override
		public int compare(FileSystemElement e1, FileSystemElement e2) {
			if (!checkInit())
				return 0;

			try {
				return compiledComparator.compare(e1, e2);
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
			implements JsonSerializer<HashMap<String, CustomSorBy>>, JsonDeserializer<HashMap<String, CustomSorBy>>
	{
		@Override
		public JsonElement serialize(HashMap<String, CustomSorBy> map, Type typeOfSrc, JsonSerializationContext context)
		{
			if (map == null)
				return context.serialize(Map.of());

			JsonObject serialisedMap = context.serialize(map).getAsJsonObject();
			serialisedMap.asMap().values().forEach(e -> e.getAsJsonObject().remove("name"));
			return serialisedMap;
		}

		@Override
		public HashMap<String, CustomSorBy> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
				throws JsonParseException
		{
			if (json == null || json.isJsonNull())
				return  new HashMap<>();

			HashMap<String, CustomSorBy> map = context.deserialize(json, typeOfT);
			map.forEach((name, sortBy) -> sortBy.name = name);
			return map;
		}
	}
}
