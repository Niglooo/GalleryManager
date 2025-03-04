package nigloo.gallerymanager.test;

import nigloo.gallerymanager.filesystem.FileSystemElement;
import nigloo.gallerymanager.filesystem.FileSystemElement.Status;
import nigloo.gallerymanager.filesystem.FileSystemService;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.test.util.ArgumentsUtil;
import nigloo.gallerymanager.test.util.TestFileSystemElement;
import nigloo.gallerymanager.test.util.TestFileSystemElement.Builder;
import nigloo.gallerymanager.test.util.TestInjectionContext;
import nigloo.tool.injection.Injector;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.commons.support.Resource;
import org.junit.platform.commons.util.ReflectionUtils;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class FileSystemServiceTest
{
    private final TestInjectionContext injectionContext  = new TestInjectionContext();

    private Path galleryPath;
    private Gallery gallery;

    @BeforeEach
    protected void setup() throws IOException
    {
        // Copying all resources
        galleryPath = Files.createTempDirectory("FileSystemServiceTest_");
        Path gallery1 = Paths.get("gallery_1");
        List<Resource> resources = ReflectionUtils.findAllResourcesInPackage(gallery1.toString(), r -> true);
        for (Resource resource : resources) {
            Path path = galleryPath.resolve(gallery1.relativize(Paths.get(resource.getName())));
            Files.createDirectories(path.getParent());
            Files.copy(resource.getInputStream(), path);
        }


        injectionContext.setInstance(new Gallery());
        Injector.addContext(injectionContext);
        Injector.ENABLE();

        // Opening the gallery AFTER setting up injection (images instances need Gallery to be injectable)
        try (Reader reader = Files.newBufferedReader(galleryPath.resolve("gallery.json"), StandardCharsets.UTF_8))
        {
            gallery = Gallery.load(reader, galleryPath);
        }
    }

    @AfterEach
    protected void cleanup() throws IOException
    {
        injectionContext.clear();
        Injector.removeContext(injectionContext);
        PathUtils.delete(galleryPath);
    }


    protected static Stream<Arguments> simpleArguments()
    {
        return ArgumentsUtil.allCombination(
                ArgumentsUtil.boolArg("keepEmptyFolder")
        );
    }

    protected static Stream<Arguments> deleteArguments()
    {
        return ArgumentsUtil.allCombination(
                ArgumentsUtil.boolArg("keepEmptyFolder"),
                ArgumentsUtil.boolArg("deleteOnDisk")
        );
    }


    @ParameterizedTest
    @MethodSource("simpleArguments")
    public void getRoot_afterConstructor_notLoadedDirectory(boolean keepEmptyFolder) throws IOException
    {
        // Given
        FileSystemService fss = new FileSystemService(keepEmptyFolder);

        // When
        FileSystemElement actual = fss.getRoot();

        // Then
        Builder b = TestFileSystemElement.builder(gallery);
        assertEquals(actual, b.directory(
                "",
                Status.NOT_LOADED
        ).build(keepEmptyFolder));
    }

    @ParameterizedTest
    @MethodSource("simpleArguments")
    public void refresh_foldersAllCases_expectedResult(boolean keepEmptyFolder) throws IOException
    {
        // Given
        FileSystemService fss = new FileSystemService(keepEmptyFolder);
        long lastUpdateBefore = fss.getRoot().getLastUpdate();

        // When
        fss.refresh(List.of(gallery.getRootFolder()), true).join();

        // Then
        Builder b = TestFileSystemElement.builder(gallery);
        assertThat(fss.getRoot().getLastUpdate()).isGreaterThan(lastUpdateBefore);
        assertEquals(fss.getRoot(), b.directory(
                "",
                Status.UNSYNC,
                b.directory(
                        "deleted",
                        Status.DELETED,
                        b.mixed(
                                "deleted_1.png",
                                Status.DELETED,
                                Status.DELETED,
                                b.image("deleted_5.png", Status.DELETED),
                                b.image("deleted_6.png", Status.DELETED)
                        ),
                        b.directory(
                                "sub",
                                Status.DELETED,
                                b.image("deleted_3.png", Status.DELETED),
                                b.image("deleted_4.png", Status.DELETED)
                        ),
                        b.image("deleted_2.png", Status.DELETED)
                ),
                b.directory(
                        "saved",
                        Status.SYNC,
                        b.directory(
                                "sub1",
                                Status.SYNC,
                                b.image("image_3.jpg", Status.SYNC),
                                b.image("image_4.jpg", Status.SYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_1.jpg", Status.SYNC),
                        b.image("image_2.jpg", Status.SYNC)
                ),
                b.directory(
                        "unsaved",
                        Status.UNSYNC,
                        b.directory(
                                "sub1",
                                Status.UNSYNC,
                                b.image("image_8.jpg", Status.UNSYNC),
                                b.image("image_9.jpg", Status.UNSYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_6.jpg", Status.UNSYNC),
                        b.image("image_7.jpg", Status.UNSYNC)
                ),
                b.mixed(
                        "image_5.jpg",
                        Status.UNSYNC,
                        Status.DELETED,
                        b.image("deleted_7.png", Status.DELETED)
                )
        ).build(keepEmptyFolder));
    }

    @ParameterizedTest
    @MethodSource("simpleArguments")
    public void refresh_imagesAllCases_expectedResult(boolean keepEmptyFolder) throws IOException
    {
        // Given
        FileSystemService fss = new FileSystemService(keepEmptyFolder);
        long lastUpdateBefore = fss.getRoot().getLastUpdate();
        List<Image> imagesToRefresh = Stream.of(
                "deleted/deleted_1.png/deleted_5.png",
                "saved/sub1/image_3.jpg",
                "unsaved/image_6.jpg"
        ).map(Paths::get).map(gallery::getImage).toList();

        // When
        fss.refresh(imagesToRefresh).join();

        // Then
        Builder b = TestFileSystemElement.builder(gallery);
        assertThat(fss.getRoot().getLastUpdate()).isGreaterThan(lastUpdateBefore);
        assertEquals(fss.getRoot(), b.directory(
                "",
                Status.NOT_FULLY_LOADED,
                b.directory(
                        "deleted",
                        Status.DELETED,
                        b.mixed(
                                "deleted_1.png",
                                Status.DELETED,
                                Status.DELETED,
                                b.image("deleted_5.png", Status.DELETED),
                                b.image("deleted_6.png", Status.DELETED)
                        ),
                        b.directory(
                                "sub",
                                Status.DELETED,
                                b.image("deleted_3.png", Status.DELETED),
                                b.image("deleted_4.png", Status.DELETED)
                        ),
                        b.image("deleted_2.png", Status.DELETED)
                ),
                b.directory(
                        "saved",
                        Status.NOT_FULLY_LOADED,
                        b.directory(
                                "sub1",
                                Status.SYNC,
                                b.image("image_3.jpg", Status.SYNC),
                                b.image("image_4.jpg", Status.SYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.NOT_LOADED
                        ),
                        b.image("image_1.jpg", Status.SYNC),
                        b.image("image_2.jpg", Status.SYNC)
                ),
                b.directory(
                        "unsaved",
                        Status.NOT_FULLY_LOADED,
                        b.directory(
                                "sub1",
                                Status.NOT_LOADED
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.NOT_LOADED
                        ),
                        b.image("image_6.jpg", Status.UNSYNC),
                        b.image("image_7.jpg", Status.UNSYNC)
                ),
                b.mixed(
                        "image_5.jpg",
                        Status.UNSYNC,
                        Status.DELETED,
                        b.image("deleted_7.png", Status.DELETED)
                )
        ).build(keepEmptyFolder));
    }

    @ParameterizedTest
    @MethodSource("simpleArguments")
    public void synchronize_allCases_expectedResult(boolean keepEmptyFolder) throws IOException
    {
        // Given
        FileSystemService fss = new FileSystemService(keepEmptyFolder);
        fss.refresh(List.of(gallery.getRootFolder()), true).join();
        long lastUpdateBefore = fss.getRoot().getLastUpdate();

        // When
        fss.synchronize(List.of(gallery.getRootFolder()), true).join();

        // Then
        assertThat(fss.getRoot().getLastUpdate()).isGreaterThan(lastUpdateBefore);
        Builder b = TestFileSystemElement.builder(gallery);
        assertEquals(fss.getRoot(), b.directory(
                "",
                Status.SYNC,
                b.directory(
                        "saved",
                        Status.SYNC,
                        b.directory(
                                "sub1",
                                Status.SYNC,
                                b.image("image_3.jpg", Status.SYNC),
                                b.image("image_4.jpg", Status.SYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_1.jpg", Status.SYNC),
                        b.image("image_2.jpg", Status.SYNC)
                ),
                b.directory(
                        "unsaved",
                        Status.SYNC,
                        b.directory(
                                "sub1",
                                Status.SYNC,
                                b.image("image_8.jpg", Status.SYNC),
                                b.image("image_9.jpg", Status.SYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_6.jpg", Status.SYNC),
                        b.image("image_7.jpg", Status.SYNC)
                ),
                b.image(
                        "image_5.jpg",
                        Status.SYNC
                )
        ).build(keepEmptyFolder));
    }

    @ParameterizedTest
    @MethodSource("simpleArguments")
    public void move_movingDir_expectedResult(boolean keepEmptyFolder) throws IOException
    {
        // Given
        FileSystemService fss = new FileSystemService(keepEmptyFolder);
        fss.refresh(List.of(gallery.getRootFolder()), true).join();
        long lastUpdateBefore = fss.getRoot().getLastUpdate();
        Path source = gallery.getRootFolder().resolve("unsaved");
        Path target = gallery.getRootFolder().resolve("saved/unsaved");

        // When
        fss.move(source, target).join();

        // Then
        assertThat(source).doesNotExist();
        assertThat(target).isDirectory();
        assertThat(fss.getRoot().getLastUpdate()).isGreaterThan(lastUpdateBefore);
        Builder b = TestFileSystemElement.builder(gallery);
        assertEquals(fss.getRoot(), b.directory(
                "",
                Status.UNSYNC,
                b.directory(
                        "deleted",
                        Status.DELETED,
                        b.mixed(
                                "deleted_1.png",
                                Status.DELETED,
                                Status.DELETED,
                                b.image("deleted_5.png", Status.DELETED),
                                b.image("deleted_6.png", Status.DELETED)
                        ),
                        b.directory(
                                "sub",
                                Status.DELETED,
                                b.image("deleted_3.png", Status.DELETED),
                                b.image("deleted_4.png", Status.DELETED)
                        ),
                        b.image("deleted_2.png", Status.DELETED)
                ),
                b.directory(
                        "saved",
                        Status.SYNC,
                        b.directory(
                                "sub1",
                                Status.SYNC,
                                b.image("image_3.jpg", Status.SYNC),
                                b.image("image_4.jpg", Status.SYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_1.jpg", Status.SYNC),
                        b.image("image_2.jpg", Status.SYNC),
                        b.directory(
                                "unsaved",
                                Status.UNSYNC,
                                b.directory(
                                        "sub1",
                                        Status.UNSYNC,
                                        b.image("image_8.jpg", Status.UNSYNC),
                                        b.image("image_9.jpg", Status.UNSYNC)
                                ),
                                b.directory(
                                        "sub2_empty",
                                        Status.EMPTY
                                ),
                                b.image("image_6.jpg", Status.UNSYNC),
                                b.image("image_7.jpg", Status.UNSYNC)
                        )
                ),
                b.mixed(
                        "image_5.jpg",
                        Status.UNSYNC,
                        Status.DELETED,
                        b.image("deleted_7.png", Status.DELETED)
                )
        ).build(keepEmptyFolder));
    }

    @ParameterizedTest
    @MethodSource("simpleArguments")
    public void move_renamingDir_expectedResult(boolean keepEmptyFolder) throws IOException
    {
        // Given
        FileSystemService fss = new FileSystemService(keepEmptyFolder);
        fss.refresh(List.of(gallery.getRootFolder()), true).join();
        long lastUpdateBefore = fss.getRoot().getLastUpdate();
        Path source = gallery.getRootFolder().resolve("unsaved");
        Path target = gallery.getRootFolder().resolve("unsaved_renamed");

        // When
        fss.move(source, target).join();

        // Then
        assertThat(source).doesNotExist();
        assertThat(target).isDirectory();
        assertThat(fss.getRoot().getLastUpdate()).isGreaterThan(lastUpdateBefore);
        Builder b = TestFileSystemElement.builder(gallery);
        assertEquals(fss.getRoot(), b.directory(
                "",
                Status.UNSYNC,
                b.directory(
                        "deleted",
                        Status.DELETED,
                        b.mixed(
                                "deleted_1.png",
                                Status.DELETED,
                                Status.DELETED,
                                b.image("deleted_5.png", Status.DELETED),
                                b.image("deleted_6.png", Status.DELETED)
                        ),
                        b.directory(
                                "sub",
                                Status.DELETED,
                                b.image("deleted_3.png", Status.DELETED),
                                b.image("deleted_4.png", Status.DELETED)
                        ),
                        b.image("deleted_2.png", Status.DELETED)
                ),
                b.directory(
                        "saved",
                        Status.SYNC,
                        b.directory(
                                "sub1",
                                Status.SYNC,
                                b.image("image_3.jpg", Status.SYNC),
                                b.image("image_4.jpg", Status.SYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_1.jpg", Status.SYNC),
                        b.image("image_2.jpg", Status.SYNC)
                ),
                b.directory(
                        "unsaved_renamed",
                        Status.UNSYNC,
                        b.directory(
                                "sub1",
                                Status.UNSYNC,
                                b.image("image_8.jpg", Status.UNSYNC),
                                b.image("image_9.jpg", Status.UNSYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_6.jpg", Status.UNSYNC),
                        b.image("image_7.jpg", Status.UNSYNC)
                ),
                b.mixed(
                        "image_5.jpg",
                        Status.UNSYNC,
                        Status.DELETED,
                        b.image("deleted_7.png", Status.DELETED)
                )
        ).build(keepEmptyFolder));
    }

    @ParameterizedTest
    @MethodSource("deleteArguments")
    public void delete_savedDir_deleted(boolean keepEmptyFolder, boolean deleteOnDisk) throws IOException
    {
        // Given
        FileSystemService fss = new FileSystemService(keepEmptyFolder);
        fss.refresh(List.of(gallery.getRootFolder()), true).join();
        long lastUpdateBefore = fss.getRoot().getLastUpdate();
        Path toDelete = gallery.getRootFolder().resolve("saved");

        // When
        fss.delete(List.of(toDelete), deleteOnDisk).join();

        // Then
        if (deleteOnDisk) {
            assertThat(toDelete).doesNotExist();
        } else {
            assertThat(toDelete).isDirectory();
        }
        assertThat(gallery.findImagesIn(toDelete, true)).isEmpty();
        assertThat(fss.getRoot().getLastUpdate()).isGreaterThan(lastUpdateBefore);
        Builder b = TestFileSystemElement.builder(gallery);
        assertEquals(fss.getRoot(), b.directory(
                "",
                Status.UNSYNC,
                b.directory(
                        "deleted",
                        Status.DELETED,
                        b.mixed(
                                "deleted_1.png",
                                Status.DELETED,
                                Status.DELETED,
                                b.image("deleted_5.png", Status.DELETED),
                                b.image("deleted_6.png", Status.DELETED)
                        ),
                        b.directory(
                                "sub",
                                Status.DELETED,
                                b.image("deleted_3.png", Status.DELETED),
                                b.image("deleted_4.png", Status.DELETED)
                        ),
                        b.image("deleted_2.png", Status.DELETED)
                ),
                b.directory(
                        "unsaved",
                        Status.UNSYNC,
                        b.directory(
                                "sub1",
                                Status.UNSYNC,
                                b.image("image_8.jpg", Status.UNSYNC),
                                b.image("image_9.jpg", Status.UNSYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_6.jpg", Status.UNSYNC),
                        b.image("image_7.jpg", Status.UNSYNC)
                ),
                b.mixed(
                        "image_5.jpg",
                        Status.UNSYNC,
                        Status.DELETED,
                        b.image("deleted_7.png", Status.DELETED)
                )
        ).build(keepEmptyFolder));
    }

    @ParameterizedTest
    @MethodSource("deleteArguments")
    public void delete_unsavedDir_deleted(boolean keepEmptyFolder, boolean deleteOnDisk) throws IOException
    {
        // Given
        FileSystemService fss = new FileSystemService(keepEmptyFolder);
        fss.refresh(List.of(gallery.getRootFolder()), true).join();
        long lastUpdateBefore = fss.getRoot().getLastUpdate();
        Path toDelete = gallery.getRootFolder().resolve("unsaved");

        // When
        fss.delete(List.of(toDelete), deleteOnDisk).join();

        // Then
        if (deleteOnDisk) {
            assertThat(toDelete).doesNotExist();
        } else {
            assertThat(toDelete).isDirectory();
        }
        assertThat(gallery.findImagesIn(toDelete, true)).isEmpty();
        assertThat(fss.getRoot().getLastUpdate()).isGreaterThan(lastUpdateBefore);
        Builder b = TestFileSystemElement.builder(gallery);
        assertEquals(fss.getRoot(), b.directory(
                "",
                Status.UNSYNC,
                b.directory(
                        "deleted",
                        Status.DELETED,
                        b.mixed(
                                "deleted_1.png",
                                Status.DELETED,
                                Status.DELETED,
                                b.image("deleted_5.png", Status.DELETED),
                                b.image("deleted_6.png", Status.DELETED)
                        ),
                        b.directory(
                                "sub",
                                Status.DELETED,
                                b.image("deleted_3.png", Status.DELETED),
                                b.image("deleted_4.png", Status.DELETED)
                        ),
                        b.image("deleted_2.png", Status.DELETED)
                ),
                b.directory(
                        "saved",
                        Status.SYNC,
                        b.directory(
                                "sub1",
                                Status.SYNC,
                                b.image("image_3.jpg", Status.SYNC),
                                b.image("image_4.jpg", Status.SYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_1.jpg", Status.SYNC),
                        b.image("image_2.jpg", Status.SYNC)
                ),
                b.mixed(
                        "image_5.jpg",
                        Status.UNSYNC,
                        Status.DELETED,
                        b.image("deleted_7.png", Status.DELETED)
                )
        ).build(keepEmptyFolder));
    }

    @ParameterizedTest
    @MethodSource("deleteArguments")
    public void delete_deletedDir_deletedFromGallery(boolean keepEmptyFolder, boolean deleteOnDisk) throws IOException
    {
        // Given
        FileSystemService fss = new FileSystemService(keepEmptyFolder);
        fss.refresh(List.of(gallery.getRootFolder()), true).join();
        long lastUpdateBefore = fss.getRoot().getLastUpdate();
        Path toDelete = gallery.getRootFolder().resolve("deleted");

        // When
        fss.delete(List.of(toDelete), deleteOnDisk).join();

        // Then
        assertThat(gallery.findImagesIn(toDelete, true)).isEmpty();
        assertThat(fss.getRoot().getLastUpdate()).isGreaterThan(lastUpdateBefore);
        Builder b = TestFileSystemElement.builder(gallery);
        assertEquals(fss.getRoot(), b.directory(
                "",
                Status.UNSYNC,
                b.directory(
                        "saved",
                        Status.SYNC,
                        b.directory(
                                "sub1",
                                Status.SYNC,
                                b.image("image_3.jpg", Status.SYNC),
                                b.image("image_4.jpg", Status.SYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_1.jpg", Status.SYNC),
                        b.image("image_2.jpg", Status.SYNC)
                ),
                b.directory(
                        "unsaved",
                        Status.UNSYNC,
                        b.directory(
                                "sub1",
                                Status.UNSYNC,
                                b.image("image_8.jpg", Status.UNSYNC),
                                b.image("image_9.jpg", Status.UNSYNC)
                        ),
                        b.directory(
                                "sub2_empty",
                                Status.EMPTY
                        ),
                        b.image("image_6.jpg", Status.UNSYNC),
                        b.image("image_7.jpg", Status.UNSYNC)
                ),
                b.mixed(
                        "image_5.jpg",
                        Status.UNSYNC,
                        Status.DELETED,
                        b.image("deleted_7.png", Status.DELETED)
                )
        ).build(keepEmptyFolder));
    }

    private static void assertEquals(FileSystemElement actual, TestFileSystemElement expected) {
        assertThat(actual).isNotNull();
        assertThat(TestFileSystemElement.valueOf(actual)).isEqualTo(expected);
    }

    protected static Stream<Arguments> computeNewStatusArguments()
    {
        // DONT_EXIST and EMPTY are not valid values for childrenStatuses
        return Stream.of(
                new Object[] {Status.LOADING,           Status.NOT_LOADED,  Set.of(Status.LOADING, Status.SYNC, Status.DELETED, Status.UNSYNC)},
                new Object[] {Status.SYNC,              Status.NOT_LOADED,  Set.of(Status.SYNC)},
                new Object[] {Status.UNSYNC,            Status.NOT_LOADED,  Set.of(Status.UNSYNC, Status.SYNC)},
                new Object[] {Status.UNSYNC,            Status.NOT_LOADED,  Set.of(Status.DELETED, Status.SYNC)},
                new Object[] {Status.UNSYNC,            Status.NOT_LOADED,  Set.of(Status.DELETED)},
                new Object[] {Status.DELETED,           Status.DELETED,     Set.of(Status.DELETED)},
                new Object[] {Status.DELETED,           Status.DONT_EXIST,  Set.of(Status.DELETED)},
                new Object[] {Status.EMPTY,             Status.NOT_LOADED,  Set.of()},
                new Object[] {Status.NOT_FULLY_LOADED,  Status.NOT_LOADED,  Set.of(Status.SYNC, Status.NOT_LOADED)},
                new Object[] {Status.NOT_FULLY_LOADED,  Status.NOT_LOADED,  Set.of(Status.SYNC, Status.NOT_FULLY_LOADED)}
        ).map(args -> Arguments.argumentSet("Expected: %s From: %s With: %s".formatted(args), args));
    }

    @ParameterizedTest
    @MethodSource("computeNewStatusArguments")
    public void computeNewStatus_parameterized_expectedResult(
            Status expectedNewStatus, Status currentStatus, Set<Status> childrenStatuses
    ) throws InvocationTargetException, IllegalAccessException
    {
        // Given
        Method computeNewStatus = ReflectionUtils.findMethod(
                FileSystemService.class,
                "computeNewStatus",
                Status.class, Set.class).orElseThrow();
        computeNewStatus.setAccessible(true);

        // When
        Status newStatus = (Status) computeNewStatus.invoke(null, currentStatus, childrenStatuses);

        // Then
        assertThat(newStatus).isEqualTo(expectedNewStatus);
    }
}
