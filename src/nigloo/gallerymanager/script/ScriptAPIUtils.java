package nigloo.gallerymanager.script;

import nigloo.gallerymanager.model.SortBy;

import java.nio.file.Path;

public enum ScriptAPIUtils
{
    INSTANCE;

    public int comparePathIgnoringExtension(Path p1, Path p2)
    {
        return SortBy.compareIgnoringExtension(p1, p2);
    }
}
