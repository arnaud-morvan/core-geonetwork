package org.fao.geonet.services.metadata.format;

import jeeves.server.ServiceConfig;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.exceptions.BadParameterEx;
import org.fao.geonet.exceptions.ResourceNotFoundEx;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.GeonetworkDataDirectory;
import org.fao.geonet.repository.MetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.fao.geonet.services.metadata.format.FormatterConstants.SCHEMA_PLUGIN_FORMATTER_DIR;
import static org.fao.geonet.services.metadata.format.FormatterConstants.VIEW_GROOVY_FILENAME;
import static org.fao.geonet.services.metadata.format.FormatterConstants.VIEW_XSL_FILENAME;

/**
 * Common constants and methods for Metadata formatter classes
 *
 * @author jeichar
 */
abstract class AbstractFormatService {
    @Autowired
    protected DataManager dataManager;
    @Autowired
    protected MetadataRepository metadataRepository;

    protected static final DirectoryStream.Filter<Path> FORMATTER_FILTER = new DirectoryStream.Filter<Path>() {
        @Override
        public boolean accept(Path file) throws IOException {
            return Files.isDirectory(file) && isViewFileExists(file);
        }

        private boolean isViewFileExists(Path file) {
            return Files.exists(file.resolve(VIEW_GROOVY_FILENAME)) || Files.exists(file.resolve(VIEW_XSL_FILENAME));
        }
    };

    public void init(Path appPath, ServiceConfig params) throws Exception {
    }

    protected Metadata loadMetadata(MetadataRepository metadataRepository, int id) {
        Metadata md = metadataRepository.findOne(id);

        if (md == null) {
            throw new IllegalArgumentException("No metadata found. id = " + id);
        }
        return md;
    }

    protected void checkLegalId(String paramName, String xslid) throws BadParameterEx {
        if (!FormatterConstants.ID_XSL_REGEX.matcher(xslid).matches()) {
            throw new BadParameterEx(paramName, "Only the following are permitted in the id" + FormatterConstants.ID_XSL_REGEX);
        }
    }

    protected static boolean containsFile(Path container, Path desiredFile) throws IOException {
        if (!Files.exists(desiredFile) || !Files.exists(container)) {
            return false;
        }

        Path canonicalDesired = desiredFile.toRealPath();
        final Path canonicalContainer = container.toRealPath();
        while (canonicalDesired.getParent() != null && !canonicalDesired.getParent().equals(canonicalContainer)) {
            canonicalDesired = canonicalDesired.getParent();
        }

        return canonicalContainer.equals(canonicalDesired.getParent());
    }

    protected Path getAndVerifyFormatDir(GeonetworkDataDirectory dataDirectory, String paramName, String xslid,
                                         Path schemaDir) throws BadParameterEx, IOException {
        if (xslid == null) {
            throw new BadParameterEx(paramName, "missing " + paramName + " param");
        }

        checkLegalId(paramName, xslid);
        Path formatDir = null;
        if (schemaDir != null && Files.exists(schemaDir)) {
            formatDir = schemaDir.resolve(SCHEMA_PLUGIN_FORMATTER_DIR).resolve(xslid);
            if (!Files.exists(formatDir)) {
                formatDir = null;
            }
        }

        Path userXslDir = dataDirectory.getFormatterDir();
        if (formatDir == null) {
            formatDir = userXslDir.resolve(xslid);
        }

        if (!Files.exists(formatDir)) {
            throw new BadParameterEx(paramName, "Format bundle " + xslid + " does not exist");
        }

        if (!Files.isDirectory(formatDir)) {
            throw new BadParameterEx(paramName, "Format bundle " + xslid + " is not a directory");
        }

        if (!Files.exists(formatDir.resolve(VIEW_XSL_FILENAME)) &&
            !Files.exists(formatDir.resolve(VIEW_GROOVY_FILENAME))) {
            throw new BadParameterEx(paramName,
                    "Format bundle " + xslid + " is not a valid format bundle because it does not have a '" +
                    VIEW_XSL_FILENAME + "' file or a '" + VIEW_GROOVY_FILENAME + "' file.");
        }

        if (!containsFile(userXslDir, formatDir)) {
            if (schemaDir == null || !containsFile(schemaDir.resolve(SCHEMA_PLUGIN_FORMATTER_DIR), formatDir)) {
                throw new BadParameterEx(paramName,
                        "Format bundle " + xslid + " is not a format bundle id because it does not reference a " +
                        "file contained within the userXslDir");
            }
        }
        return formatDir;
    }

    protected String resolveId(String id) throws Exception {
        String resolvedId;
            try {
                Integer.parseInt(id);
                resolvedId = id;
            } catch (NumberFormatException e) {
                try {
                    resolvedId = dataManager.getMetadataId(id);
                } catch (Exception e2) {
                    throw new ResourceNotFoundEx("Metadata with id: id");
                }
            }

        return resolvedId;
    }
}
