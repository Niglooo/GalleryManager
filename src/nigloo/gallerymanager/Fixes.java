package nigloo.gallerymanager;

import javafx.scene.media.Media;
import nigloo.gallerymanager.autodownloader.Downloader;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.tool.Utils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.Character.UnicodeBlock;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Fixes
{
    static boolean stderrError = false;

    static public void removeBadFiles(Gallery gallery, Path path, Downloader downloader) throws Exception
    {
        System.setErr(new PrintStream(new OutputStream()
        {
            @Override
            public void write(int b) throws IOException
            {
                stderrError = true;
            }
        }));

        System.err.flush();
        stderrError = false;

        Files.walk(path).forEach(file -> {
            if (Files.isDirectory(file))
                return;

            if (Image.isImage(file)) {
                boolean valid;
                System.out.print(file);
                System.out.flush();
                if (Image.isActuallyVideo(file)) {
                    valid = isValidVideo(file);
                } else {
                    valid = isValidImage(file);
                }
                System.out.println("\t\t=> valid: "+valid);
                if (!valid)
                {
                    Image image = gallery.findImage(file, false);
                    if (image != null)
                    {
                        downloader.removeMapping(image);
                        gallery.deleteImages(List.of(image));
                    }
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            else if (Downloader.isZip(file.getFileName().toString())) {
                System.out.print(file);
                System.out.flush();
                boolean valid = isValidZip(file);
                System.out.println("\t\t=> valid: "+valid);
                if (!valid)
                {
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            else {
                System.out.println("Ignored: "+file);
            }
        });
    }

    static boolean isValidImage(Path file) {
        try {
            javafx.scene.image.Image image = new javafx.scene.image.Image(file.toUri().toString());
            boolean isError = image.isError();
            System.err.flush();
            if (stderrError) {
                isError = true;
            }
            stderrError = false;
            return !isError;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isValidVideo(Path file) {
        try {
            Media media = new Media(file.toUri().toString());
            boolean isError = media.getError() == null;
            return !isError;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isValidZip(Path filePath) {
        try {
            ZipInputStream zis = new ZipInputStream(Files.newInputStream(filePath));
            ZipEntry zipEntry;
            try {
                zipEntry= zis.getNextEntry();
                if(zipEntry==null) {
                    return false;
                }
            } catch (Exception e1) {
                Utils.closeQuietly(zis);
                try {
                    zis = new ZipInputStream(Files.newInputStream(filePath), Charset.forName("Shift-JIS"));
                    zipEntry= zis.getNextEntry();

                    String name = zipEntry.getName();
                    if (name.lastIndexOf('.') >= 0)
                        name = name.substring(0, name.lastIndexOf('.'));

                    Map<String, Long> counts = name.codePoints()
                                                   .mapToObj(Integer::valueOf)
                                                   .collect(Collectors.groupingBy(codepoint ->
                                                                                  {
                                                                                      UnicodeBlock block = UnicodeBlock.of(codepoint);
                                                                                      if (block == UnicodeBlock.HIRAGANA
                                                                                              || block == UnicodeBlock.KATAKANA
                                                                                              || block == UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                                                                                              || block.toString().contains("CJK_") /*Chinese/Japanese/Korean*/)
                                                                                          return "japanese";
                                                                                      else if (block == UnicodeBlock.BASIC_LATIN)
                                                                                          return "basic-latin";
                                                                                      else
                                                                                          return "other";
                                                                                  }, Collectors.counting()));

                    double total = name.length();
                    double nbJapanese = counts.getOrDefault("japanese", 0L);
                    double nbBasicLatin = counts.getOrDefault("basic-latin", 0L);

                    // Less japanese than half are japanese and basic latin less than 70% of non japanese
                    if (nbJapanese < total / 2d && nbBasicLatin / (total - nbJapanese) < 0.7d)
                    {
                        throw new IllegalArgumentException("Not japanese: " + zipEntry.getName());
                    }
                } catch (Exception e2) {
                    Utils.closeQuietly(zis);
                    return false;
                }
            }
            while (zipEntry != null)
            {
                if (zipEntry.isDirectory())
                {

                }
                else
                {
                    zis.readAllBytes();
                }
                zipEntry = zis.getNextEntry();
            }

        } catch (Exception e) {
            return false;
        }

        return true;
    }
}
