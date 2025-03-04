package nigloo.gallerymanager.test.util;

import nigloo.gallerymanager.filesystem.FileSystemElement;
import nigloo.gallerymanager.filesystem.FileSystemElement.Status;
import nigloo.gallerymanager.model.Gallery;
import nigloo.tool.PrintString;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public record TestFileSystemElement(Path path, Status statusImage, Status statusDirectory, Set<TestFileSystemElement> children)
{
    public TestFileSystemElement {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(statusImage, "statusImage");
        Objects.requireNonNull(statusDirectory, "statusDirectory");
        Objects.requireNonNull(children, "children");
        children.forEach(Objects::requireNonNull);
    }

    public static TestFileSystemElement valueOf(FileSystemElement element) {
        return new TestFileSystemElement(
                element.getPath(),
                element.getStatusImage(),
                element.getStatusDirectory(),
                element.getChildren()
                       .stream()
                       .map(TestFileSystemElement::valueOf)
                       .collect(Collectors.toUnmodifiableSet())
        );
    }

    public static Builder builder(Gallery gallery) {
        return new Builder(gallery, null, null, null);
    }

    public record Builder(Gallery gallery, String path, Status statusImage, Status statusDirectory, Builder... children) {

        public Builder image(
                String path,
                Status status
        ) {
            return mixed(path, status, Status.DONT_EXIST);
        }

        public Builder directory(
                String path,
                Status status,
                Builder... children
        ) {
            return mixed(path, Status.DONT_EXIST, status, children);
        }
        public Builder mixed(
                String path,
                Status statusImage,
                Status statusDirectory,
                Builder... children
        ) {
            return new Builder(
                    gallery,
                    path,
                    statusImage,
                    statusDirectory,
                    children
            );
        }

        public TestFileSystemElement build(boolean keepEmptyFolder) {
            if (path == null) {
                throw new IllegalStateException("Empty builder");
            }
            return build(null, keepEmptyFolder);
        }

        private TestFileSystemElement build(Path parentPath, boolean keepEmptyFolder) {
            Path path = Paths.get(this.path);
            Path fullPath = gallery.toAbsolutePath(parentPath != null ? parentPath.resolve(path) : path);
            return new TestFileSystemElement(
                    fullPath,
                    statusImage,
                    statusDirectory,
                    Stream.of(children)
                          .map(child -> child.build(fullPath, keepEmptyFolder))
                          .filter(child -> keepEmptyFolder || child.statusDirectory != Status.EMPTY || child.statusImage != Status.DONT_EXIST)
                          .collect(Collectors.toUnmodifiableSet())
            );
        }
    }

    @Override
    public String toString()
    {
        PrintString sb = new PrintString();
        toString(sb, 0);
        return sb.toString();
    }

    private void toString(PrintString out, int indentLevel)
    {
        ArrayList<String> statuses = new ArrayList<>(2);
        if (statusDirectory != Status.DONT_EXIST) {
            statuses.add("DIR="+statusDirectory);
        }
        if (statusImage != Status.DONT_EXIST) {
            statuses.add("FILE="+statusImage);
        }

        out.print("  ".repeat(indentLevel));
        out.print(path.getFileName());
        out.print(" ");
        out.print(statuses);
        out.println();

        children.stream().sorted(Comparator.comparing(TestFileSystemElement::path)).forEach(child -> child.toString(out, indentLevel+1));
    }
}
