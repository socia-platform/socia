package controllers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import managers.FolderManager;
import managers.GroupManager;
import managers.MediaManager;
import models.Folder;
import models.Group;
import models.Media;
import models.services.NotificationService;
import play.data.Form;
import play.db.jpa.Transactional;
import play.mvc.Call;
import play.mvc.Http;
import play.mvc.Http.MultipartFormData;
import play.mvc.Http.MultipartFormData.FilePart;
import play.mvc.Result;
import play.mvc.Security;

import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


@Security.Authenticated(Secured.class)
public class MediaController extends BaseController {

    @Inject
    MediaManager mediaManager;

    @Inject
    GroupManager groupManager;

    @Inject
    FolderManager folderManager;

    final static String tempPrefix = "htwplus_temp";
    private Config conf = ConfigFactory.load();

    @Transactional(readOnly = true)
    public Result view(Long mediaId, String action) {
        Media media = mediaManager.findById(mediaId);
        if (Secured.viewMedia(media)) {
            switch (action) {
                case "show":
                    response().setHeader("Content-Disposition", "inline; filename=\"" + media.fileName + "\"");
                    break;
                case "download":
                    response().setHeader("Content-Disposition", "attachment; filename=\"" + media.fileName + "\"");
                    break;
            }
            return ok(media.file);
        } else {
            flash("error", "Dazu hast du keine Berechtigung!");
            return Secured.nullRedirect(request());
        }
    }

    @Transactional
    public Result delete(Long id) {
        Media media = mediaManager.findById(id);


        Call ret = controllers.routes.Application.index();
        if (media.folder != null) {
            Long folderId = media.folder.id;
            Long groupId = media.findGroup().id;

            if (!Secured.deleteMedia(media)) {
                return redirect(controllers.routes.Application.index());
            }
            ret = routes.GroupController.media(groupId, folderId);
        }

        mediaManager.delete(media);
        flash("success", "Datei " + media.title + " erfolgreich gelöscht!");
        return redirect(ret);
    }

    @Transactional(readOnly = true)
    public Result multiView(String target, Long id) {

        String[] action = request().body().asFormUrlEncoded().get("action");
        Call ret = controllers.routes.Application.index();
        Group group = null;

        if (target.equals(Media.GROUP)) {
            group = groupManager.findById(id);
            if (!Secured.viewGroup(group)) {
                return redirect(controllers.routes.Application.index());
            }
            ret = controllers.routes.GroupController.media(id, 0L);
        } else {
            return redirect(ret);
        }

        String[] mediaselection = request().body().asFormUrlEncoded().get("mediaSelection");
        String[] folderSelection = request().body().asFormUrlEncoded().get("folderSelection");

        if (mediaselection == null && folderSelection == null) {
            flash("error", "Bitte wähle mindestens eine Datei aus.");
            return redirect(ret);
        }

        if (action[0].equals("delete")) {

            // delete media files
            if (mediaselection != null) {
                for (String s : mediaselection) {
                    Media media = mediaManager.findById(Long.parseLong(s));
                    if (Secured.deleteMedia(media)) {
                        mediaManager.delete(media);
                    }
                }
            }

            // delete folder and files
            if (folderSelection != null) {
                for (String folderId : folderSelection) {
                    Folder folder = folderManager.findById(Long.parseLong(folderId));
                    if (Secured.deleteFolder(folder)) {
                        folderManager.delete(folder);
                    }
                }
            }
        }

        if (action[0].equals("download")) {

            String filename = createFileName(group.title);
            List<Media> mediaList = new ArrayList<>();

            // grab media files
            if (mediaselection != null) {
                for (String s : mediaselection) {
                    Media media = mediaManager.findById(Long.parseLong(s));
                    if (Secured.viewMedia(media)) {
                        mediaList.add(media);
                    }
                }
            }

            // grab folder files
            if (folderSelection != null) {
                for (String folderId : folderSelection) {
                    Folder folder = folderManager.findById(Long.parseLong(folderId));
                    if (Secured.viewFolder(folder)) {
                        mediaList.addAll(folderManager.getAllMedia(folder));
                    }
                }
            }

            try {
                File file = createZIP(mediaList, filename);
                response().setHeader("Content-disposition", "attachment; filename=\"" + filename + "\"");
                return ok(file);
            } catch (IOException e) {
                flash("error", "Etwas ist schiefgegangen. Bitte probiere es noch einmal!");
                return redirect(ret);
            }
        }
        return redirect(ret);
    }

    private String createFileName(String prefix) {
        return prefix + "-" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + ".zip";
    }


    private File createZIP(List<Media> media, String fileName) throws IOException {

        //cleanUpTemp(); // JUST FOR DEVELOPMENT, DO NOT USE IN PRODUCTION
        String tmpPath = conf.getString("media.tempPath");
        File file = File.createTempFile(tempPrefix, ".tmp", new File(tmpPath));

        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(file));
        zipOut.setLevel(Deflater.NO_COMPRESSION);
        byte[] buffer = new byte[4092];
        int byteCount = 0;
        for (Media m : media) {
            zipOut.putNextEntry(new ZipEntry(m.fileName));
            FileInputStream fis = new FileInputStream(m.file);
            byteCount = 0;
            while ((byteCount = fis.read(buffer)) != -1) {
                zipOut.write(buffer, 0, byteCount);
            }
            fis.close();
            zipOut.closeEntry();
        }

        zipOut.flush();
        zipOut.close();
        return file;
    }

    /**
     * New file is uploaded.
     *
     * @param target Target of the file (e.g. "group")
     * @return Result
     */
    @Transactional
    public Result upload(String target, Long folderId) {
        final int maxTotalSize = conf.getInt("media.maxSize.total");
        final int maxFileSize = conf.getInt("media.maxSize.file");

        Call ret = controllers.routes.Application.index();
        Group group;
        Folder folder = folderManager.findById(folderId);

        // Where to put the media
        if (target.equals(Media.GROUP)) {
            group = groupManager.findById(folder.findRoot(folder).group.id);
            if (!Secured.uploadMedia(group)) {
                return redirect(controllers.routes.Application.index());
            }
            ret = controllers.routes.GroupController.media(group.id, folderId);
        } else {
            return redirect(ret);
        }

        // Is it to big in total?
        String[] contentLength = request().headers().get("Content-Length");
        if (contentLength != null) {
            int size = Integer.parseInt(contentLength[0]);
            if (mediaManager.byteAsMB(size) > maxTotalSize) {
                flash("error", "Du darfst auf einmal nur " + maxTotalSize + " MB hochladen.");
                return redirect(ret);
            }
        } else {
            flash("error", "Etwas ist schiefgegangen. Bitte probiere es noch einmal!");
            return redirect(ret);
        }

        // Get the data
        MultipartFormData body = request().body().asMultipartFormData();
        List<Http.MultipartFormData.FilePart> uploads = body.getFiles();

        List<Media> mediaList = new ArrayList<Media>();

        if (!uploads.isEmpty()) {

            // Create the Media models and perform some checks
            for (FilePart upload : uploads) {

                Media med = new Media();
                med.title = upload.getFilename();
                med.mimetype = upload.getContentType();
                med.fileName = upload.getFilename();
                med.file = upload.getFile();
                med.owner = Component.currentAccount();

                if (mediaManager.byteAsMB(med.file.length()) > maxFileSize) {
                    flash("error", "Die Datei " + med.title + " ist größer als " + maxFileSize + " MB!");
                    return redirect(ret);
                }

                String error = "Eine Datei mit dem Namen " + med.title + " existiert bereits";
                if (target.equals(Media.GROUP)) {
                    med.folder = folder;
                    med.temporarySender = Component.currentAccount();
                    if (mediaManager.existsInFolder(med.title, folder)) {
                        flash("error", error);
                        return redirect(ret);
                    }
                }
                mediaList.add(med);
            }

            for (Media m : mediaList) {
                try {
                    mediaManager.create(m);

                    // create group notification, if a group exists
                    if (m.group != null) {
                        NotificationService.getInstance().createNotification(m, Media.MEDIA_NEW_MEDIA);
                    }
                } catch (Exception e) {
                    return internalServerError(e.getMessage());
                }
            }
            flash("success", "Datei(en) erfolgreich hinzugefügt.");
            return redirect(ret);
        } else {
            flash("error", "Etwas ist schiefgegangen. Bitte probiere es noch einmal!");
            return redirect(ret);
        }
    }
}