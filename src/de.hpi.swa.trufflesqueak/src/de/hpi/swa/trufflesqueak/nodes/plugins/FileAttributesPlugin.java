/*
 * Copyright (c) 2026 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2026 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */

package de.hpi.swa.trufflesqueak.nodes.plugins;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import de.hpi.swa.trufflesqueak.exceptions.PrimitiveFailed;
import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.model.BooleanObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.model.NilObject;
import de.hpi.swa.trufflesqueak.model.PointersObject;
import de.hpi.swa.trufflesqueak.nodes.primitives.AbstractPrimitiveFactoryHolder;
import de.hpi.swa.trufflesqueak.nodes.primitives.AbstractPrimitiveNode;
import de.hpi.swa.trufflesqueak.nodes.primitives.Primitive.Primitive0;
import de.hpi.swa.trufflesqueak.nodes.primitives.Primitive.Primitive1WithFallback;
import de.hpi.swa.trufflesqueak.nodes.primitives.Primitive.Primitive2WithFallback;
import de.hpi.swa.trufflesqueak.nodes.primitives.SqueakPrimitive;
import de.hpi.swa.trufflesqueak.util.OS;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class FileAttributesPlugin extends AbstractPrimitiveFactoryHolder {

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveFileMasks")
    protected abstract static class PrimFileMasksNode extends AbstractPrimitiveNode implements Primitive0 {
        private static final long S_IFMT = 0170000L;
        private static final long S_IFSOCK = 0140000L;
        private static final long S_IFLNK = 0120000L;
        private static final long S_IFREG = 0100000L;
        private static final long S_IFBLK = 0060000L;
        private static final long S_IFDIR = 0040000L;
        private static final long S_IFCHR = 0020000L;
        private static final long S_IFIFO = 0010000L;

        @CompilationFinal(dimensions = 1) private static final Object[] MASK_TEMPLATE = computeMasks();

        private static Object[] computeMasks() {
            final Object[] masks = new Object[8];

            Arrays.fill(masks, NilObject.SINGLETON);

            masks[0] = S_IFMT;

            if (!OS.isWindows()) {
                masks[1] = S_IFSOCK;
                masks[2] = S_IFLNK;
            }

            masks[3] = S_IFREG;
            masks[4] = S_IFBLK;
            masks[5] = S_IFDIR;
            masks[6] = S_IFCHR;
            masks[7] = S_IFIFO;

            return masks;
        }

        @Specialization
        protected static final Object fileMasks(@SuppressWarnings("unused") final Object receiver, @Bind final SqueakImageContext image) {
            return image.asArrayOfObjects(MASK_TEMPLATE.clone());
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveFileExists")
    protected abstract static class PrimFileExistsNode extends AbstractPrimitiveNode implements Primitive1WithFallback {
        @Specialization(guards = "path.isByteType()")
        protected static final boolean fileExists(@SuppressWarnings("unused") final Object receiver, final NativeObject path,
                        @Bind final SqueakImageContext image) {
            return BooleanObject.wrap(image.env.getPublicTruffleFile(path.asStringUnsafe()).exists());
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveFileAttribute")
    protected abstract static class PrimFileAttributeNode extends AbstractPrimitiveNode implements Primitive2WithFallback {
        private static final long UNIX_TO_SQUEAK_TIME_OFFSET = 2177452800L;
        private static final LinkOption[] NO_FOLLOW = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

        @Specialization(guards = {"path.isByteType()", "attributeNumber >= 1", "attributeNumber <= 16"})
        protected static final Object fileAttribute(@SuppressWarnings("unused") final Object receiver, final NativeObject path,
                        final long attributeNumber,
                        @Bind final SqueakImageContext image) {
            final TruffleFile file = image.env.getPublicTruffleFile(path.asStringUnsafe());
            final Object result = getFileAttribute(file, (int) attributeNumber);

            if (result == null) {
                throw PrimitiveFailed.GENERIC_ERROR;
            }

            return result;
        }

        private static Object getFileAttribute(final TruffleFile file, final int attrNum) {
            // Cases 13-16: Access and Symlink Checks
            if (attrNum >= 13 && attrNum <= 16) {
                switch (attrNum) {
                    case 13:
                        return BooleanObject.wrap(file.isReadable());
                    case 14:
                        return BooleanObject.wrap(file.isWritable());
                    case 15:
                        return BooleanObject.wrap(file.isExecutable());
                    case 16:
                        return BooleanObject.wrap(file.isSymbolicLink());
                }
            }

            // Cases 1-12: Require stat-like file existence
            if (!file.exists(NO_FOLLOW)) {
                return null;
            }

            try {
                return switch (attrNum) {
                    case 1 -> NilObject.SINGLETON; // fileName
                    case 2 -> getPosixMode(file); // Mode
                    case 3, // inode
                        4, // device id
                        5, // nlink
                        6, // uid
                        7 // gid
                        -> 0L; // TODO: implement for Unix
                    case 8 -> {
                        if (file.isDirectory(NO_FOLLOW)) {
                            yield 0L;
                        }
                        yield file.size(NO_FOLLOW);
                    }
                    case 9 -> file.getLastAccessTime(NO_FOLLOW).to(TimeUnit.SECONDS) + UNIX_TO_SQUEAK_TIME_OFFSET; // access time
                    case 10 -> file.getLastModifiedTime(NO_FOLLOW).to(TimeUnit.SECONDS) + UNIX_TO_SQUEAK_TIME_OFFSET; // modified time
                    case 11 -> file.getCreationTime(NO_FOLLOW).to(TimeUnit.SECONDS) + UNIX_TO_SQUEAK_TIME_OFFSET; // change time
                    case 12 -> NilObject.SINGLETON; // creation time
                    default -> null;
                };
            } catch (IOException | SecurityException e) {
                return null;
            }
        }

        private static long getPosixMode(final TruffleFile file) {
            long mode = 0;

            if (file.isSymbolicLink()) {
                mode |= 0120000L;
            } else if (file.isDirectory(NO_FOLLOW)) {
                mode |= 0040000L;
            } else if (file.isRegularFile(NO_FOLLOW)) {
                mode |= 0100000L;
            }

            try {
                final Set<PosixFilePermission> perms = file.getPosixPermissions(NO_FOLLOW);
                for (PosixFilePermission perm : perms) {
                    switch (perm) {
                        case OWNER_READ:
                            mode |= 0400L;
                            break;
                        case OWNER_WRITE:
                            mode |= 0200L;
                            break;
                        case OWNER_EXECUTE:
                            mode |= 0100L;
                            break;
                        case GROUP_READ:
                            mode |= 0040L;
                            break;
                        case GROUP_WRITE:
                            mode |= 0020L;
                            break;
                        case GROUP_EXECUTE:
                            mode |= 0010L;
                            break;
                        case OTHERS_READ:
                            mode |= 0004L;
                            break;
                        case OTHERS_WRITE:
                            mode |= 0002L;
                            break;
                        case OTHERS_EXECUTE:
                            mode |= 0001L;
                            break;
                    }
                }
            } catch (UnsupportedOperationException | IOException e) {
                // Windows fallback
                if (file.isReadable()) {
                    mode |= 0444L;
                }
                if (file.isWritable()) {
                    mode |= 0222L;
                }
                if (file.isExecutable()) {
                    mode |= 0111L;
                }
            }

            return mode;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveOpendir")
    protected abstract static class PrimOpendirNode extends AbstractPrimitiveNode implements Primitive1WithFallback {
        @Specialization(guards = "path.isByteType()")
        @TruffleBoundary
        protected static final Object doOpen(@SuppressWarnings("unused") final Object receiver, final NativeObject path,
                        @Bind final SqueakImageContext image) {
            final String pathStr = path.asStringUnsafe();
            final TruffleFile file = image.env.getPublicTruffleFile(pathStr);
            if (!file.isDirectory()) {
                throw PrimitiveFailed.andTransferToInterpreter();
            }
            try {
                final List<TruffleFile> entries = new ArrayList<>();
                try (DirectoryStream<TruffleFile> stream = file.newDirectoryStream()) {
                    for (final TruffleFile entry : stream) {
                        entries.add(entry);
                    }
                }
                final Iterator<TruffleFile> iterator = entries.iterator();
                final PointersObject handle = PointersObject.newHandleWithHiddenObject(image, iterator);
                if (iterator.hasNext()) {
                    final Object[] entryData = makeEntryData(image, iterator.next());
                    return image.asArrayOfObjects(entryData[0], entryData[1], handle);
                }
                return handle;
            } catch (final IOException | SecurityException e) {
                throw PrimitiveFailed.andTransferToInterpreter();
            }
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveReaddir")
    protected abstract static class PrimReaddirNode extends AbstractPrimitiveNode implements Primitive1WithFallback {
        @Specialization
        @TruffleBoundary
        protected static final Object doRead(@SuppressWarnings("unused") final Object receiver, final PointersObject handle,
                        @Bind final SqueakImageContext image) {
            final Object hidden = handle.getHiddenObject();
            if (!(hidden instanceof Iterator)) {
                throw PrimitiveFailed.andTransferToInterpreter();
            }
            @SuppressWarnings("unchecked")
            final Iterator<TruffleFile> iterator = (Iterator<TruffleFile>) hidden;
            if (!iterator.hasNext()) {
                return NilObject.SINGLETON;
            }
            final Object[] entryData = makeEntryData(image, iterator.next());
            return image.asArrayOfObjects(entryData);
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveClosedir")
    protected abstract static class PrimClosedirNode extends AbstractPrimitiveNode implements Primitive1WithFallback {
        @Specialization
        protected static final Object doClose(@SuppressWarnings("unused") final Object receiver, final PointersObject handle) {
            return handle;
        }
    }

    private static Object[] makeEntryData(final SqueakImageContext image, final TruffleFile file) {
        final String name = file.getName();
        final Object nameObj = image.asByteString(name != null ? name : "");
        final Object[] attrs = new Object[12];
        Arrays.fill(attrs, NilObject.SINGLETON);
        attrs[1] = getFileMode(file);
        attrs[7] = safeFileSize(file);
        return new Object[]{nameObj, image.asArrayOfObjects(attrs)};
    }

    private static long getFileMode(final TruffleFile file) {
        long mode = 0;
        if (file.isSymbolicLink()) {
            mode |= 0120000L;
        } else if (file.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            mode |= 0040000L;
        } else if (file.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            mode |= 0100000L;
        }
        if (file.isReadable()) {
            mode |= 0444L;
        }
        if (file.isWritable()) {
            mode |= 0222L;
        }
        if (file.isExecutable()) {
            mode |= 0111L;
        }
        return mode;
    }

    private static long safeFileSize(final TruffleFile file) {
        try {
            return file.size();
        } catch (final IOException | SecurityException e) {
            return 0L;
        }
    }

    @Override
    public List<? extends NodeFactory<? extends AbstractPrimitiveNode>> getFactories() {
        return FileAttributesPluginFactory.getFactories();
    }
}
