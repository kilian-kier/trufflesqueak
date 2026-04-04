/*
 * Copyright (c) 2017-2026 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2026 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.plugins;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;

import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.model.BooleanObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.model.NilObject;
import de.hpi.swa.trufflesqueak.nodes.primitives.AbstractPrimitiveFactoryHolder;
import de.hpi.swa.trufflesqueak.nodes.primitives.AbstractPrimitiveNode;
import de.hpi.swa.trufflesqueak.nodes.primitives.Primitive.Primitive0;
import de.hpi.swa.trufflesqueak.nodes.primitives.Primitive.Primitive1WithFallback;
import de.hpi.swa.trufflesqueak.nodes.primitives.Primitive.Primitive2WithFallback;
import de.hpi.swa.trufflesqueak.nodes.primitives.SqueakPrimitive;
import de.hpi.swa.trufflesqueak.util.MiscUtils;

/**
 * Pharo uses module name 'FileAttributesPlugin' for file attribute primitives. This class registers
 * the same primitives as {@link FilePlugin} but under the correct module name for Pharo.
 */
public final class FileAttributesPlugin extends AbstractPrimitiveFactoryHolder {

    private static final AtomicLong NEXT_DIR_ID = new AtomicLong(1L);
    private static final ConcurrentHashMap<Long, DirStreamEntry> OPEN_DIRS = new ConcurrentHashMap<>();

    private record DirStreamEntry(DirectoryStream<TruffleFile> stream, Iterator<TruffleFile> iterator) {
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveFileExists")
    protected abstract static class PrimFileExistsNode extends AbstractPrimitiveNode implements Primitive1WithFallback {
        @Specialization(guards = "path.isByteType()")
        @TruffleBoundary(transferToInterpreterOnException = false)
        protected static final boolean doExists(@SuppressWarnings("unused") final Object receiver, final NativeObject path,
                        @Bind final SqueakImageContext image) {
            return BooleanObject.wrap(image.env.getPublicTruffleFile(path.asStringUnsafe()).exists());
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveFileAttribute")
    protected abstract static class PrimFileAttributeNode extends AbstractPrimitiveNode implements Primitive2WithFallback {
        @Specialization(guards = {"path.isByteType()", "attributeNumber >= 1", "attributeNumber <= 16"})
        @TruffleBoundary(transferToInterpreterOnException = false)
        protected static final Object doAttribute(@SuppressWarnings("unused") final Object receiver, final NativeObject path,
                        final long attributeNumber,
                        @Bind final SqueakImageContext image) {
            final TruffleFile file = image.env.getPublicTruffleFile(path.asStringUnsafe());
            final boolean exists = file.exists();
            if (!exists && attributeNumber == 16) {
                return BooleanObject.FALSE;
            }
            if (!exists && attributeNumber != 2) {
                return NilObject.SINGLETON;
            }
            return switch ((int) attributeNumber) {
                case 1 -> NilObject.SINGLETON;
                case 2 -> exists ? getPosixMode(file) : 0L;
                case 3, 4, 5, 6, 7 -> 0L;
                case 8 -> exists ? safeSize(file) : 0L;
                case 9 -> exists ? fileTimeOrZero(file, 'A') : 0L;
                case 10 -> exists ? fileTimeOrZero(file, 'M') : 0L;
                case 11 -> exists ? fileTimeOrZero(file, 'C') : 0L;
                case 12 -> exists ? fileTimeOrZero(file, 'R') : 0L;
                case 13 -> BooleanObject.wrap(exists && file.isReadable());
                case 14 -> BooleanObject.wrap(exists && file.isWritable());
                case 15 -> BooleanObject.wrap(exists && file.isExecutable());
                case 16 -> BooleanObject.wrap(exists && Files.isSymbolicLink(Paths.get(path.asStringUnsafe())));
                default -> NilObject.SINGLETON;
            };
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveFileAttributes")
    protected abstract static class PrimFileAttributesNode extends AbstractPrimitiveNode implements Primitive2WithFallback {
        @Specialization(guards = "path.isByteType()")
        @TruffleBoundary(transferToInterpreterOnException = false)
        protected static final Object doAttributes(@SuppressWarnings("unused") final Object receiver, final NativeObject path,
                        final long attributeMask,
                        @Bind final SqueakImageContext image) {
            final boolean getStats = (attributeMask & 1L) != 0;
            final boolean getAccess = (attributeMask & 2L) != 0;
            final TruffleFile file = image.env.getPublicTruffleFile(path.asStringUnsafe());
            final boolean exists = file.exists();

            Object statsArray = NilObject.SINGLETON;
            if (getStats) {
                final Object[] values = new Object[13];
                values[0] = NilObject.SINGLETON; // symlink target
                values[1] = exists ? getPosixMode(file) : 0L;
                for (int i = 2; i <= 6; i++) {
                    values[i] = 0L; // ino, dev, nlink, uid, gid
                }
                values[7] = exists ? safeSize(file) : 0L;
                values[8] = exists ? fileTimeOrZero(file, 'A') : 0L;
                values[9] = exists ? fileTimeOrZero(file, 'M') : 0L;
                values[10] = exists ? fileTimeOrZero(file, 'C') : 0L;
                values[11] = exists ? fileTimeOrZero(file, 'R') : 0L;
                values[12] = NilObject.SINGLETON; // win32 flags
                statsArray = image.asArrayOfObjects(values);
            }

            Object accessArray = NilObject.SINGLETON;
            if (getAccess) {
                accessArray = image.asArrayOfObjects(
                                BooleanObject.wrap(exists && file.isReadable()),
                                BooleanObject.wrap(exists && file.isWritable()),
                                BooleanObject.wrap(exists && file.isExecutable()));
            }

            if (getStats && getAccess) {
                return image.asArrayOfObjects(statsArray, accessArray);
            } else if (getStats) {
                return statsArray;
            } else if (getAccess) {
                return accessArray;
            } else {
                return NilObject.SINGLETON;
            }
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveFileMasks")
    protected abstract static class PrimFileMasksNode extends AbstractPrimitiveNode implements Primitive0 {
        @Specialization
        protected final Object doMasks(@SuppressWarnings("unused") final Object receiver) {
            final SqueakImageContext image = getContext();
            return image.asArrayOfObjects(
                            0170000L, // S_IFMT
                            0140000L, // S_IFSOCK
                            0120000L, // S_IFLNK
                            0100000L, // S_IFREG
                            0060000L, // S_IFBLK
                            0040000L, // S_IFDIR
                            0020000L, // S_IFCHR
                            0010000L // S_IFIFO
            );
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveOpendir")
    protected abstract static class PrimOpendirNode extends AbstractPrimitiveNode implements Primitive1WithFallback {
        @Specialization(guards = "path.isByteType()")
        @TruffleBoundary(transferToInterpreterOnException = false)
        protected static final Object doOpen(@SuppressWarnings("unused") final Object receiver, final NativeObject path,
                        @Bind final SqueakImageContext image) {
            final TruffleFile dir = image.env.getPublicTruffleFile(path.asStringUnsafe());
            if (!dir.isDirectory()) {
                return NilObject.SINGLETON;
            }
            try {
                final DirectoryStream<TruffleFile> stream = dir.newDirectoryStream();
                final Iterator<TruffleFile> iter = stream.iterator();
                final long id = NEXT_DIR_ID.getAndIncrement();
                OPEN_DIRS.put(id, new DirStreamEntry(stream, iter));
                final byte[] idBytes = new byte[8];
                ByteBuffer.wrap(idBytes).order(ByteOrder.LITTLE_ENDIAN).putLong(id);
                final NativeObject dirPointer = image.asByteArray(idBytes);
                /*
                 * Pharo's DiskStore expects primitiveOpendir to return a 3-element Array:
                 * {firstEntryName, firstEntryStatArray, dirHandle}
                 * The result doubles as the first directory entry (elements 1-2) plus
                 * the opaque dir handle (element 3) for subsequent readdir/closedir calls.
                 */
                return readFirstEntry(iter, image, dirPointer);
            } catch (final IOException e) {
                return NilObject.SINGLETON;
            }
        }

        private static Object readFirstEntry(final Iterator<TruffleFile> iter, final SqueakImageContext image, final NativeObject dirPointer) {
            while (iter.hasNext()) {
                final TruffleFile file = iter.next();
                if (!file.exists()) {
                    continue;
                }
                final String name = file.getName();
                if (name == null) {
                    continue;
                }
                final NativeObject nameObj = image.asByteArray(MiscUtils.stringToBytes(name));
                final Object[] stat = buildStatArray(file);
                return image.asArrayOfObjects(nameObj, image.asArrayOfObjects(stat), dirPointer);
            }
            return NilObject.SINGLETON;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveReaddir")
    protected abstract static class PrimReaddirNode extends AbstractPrimitiveNode implements Primitive1WithFallback {
        @Specialization(guards = "dirPointer.isByteType()")
        @TruffleBoundary(transferToInterpreterOnException = false)
        protected static final Object doRead(@SuppressWarnings("unused") final Object receiver, final NativeObject dirPointer,
                        @Bind final SqueakImageContext image) {
            final long id = ByteBuffer.wrap(dirPointer.getByteStorage()).order(ByteOrder.LITTLE_ENDIAN).getLong();
            final DirStreamEntry entry = OPEN_DIRS.get(id);
            if (entry == null) {
                return NilObject.SINGLETON;
            }
            while (entry.iterator().hasNext()) {
                final TruffleFile file = entry.iterator().next();
                if (!file.exists()) {
                    continue;
                }
                final String name = file.getName();
                if (name == null) {
                    continue;
                }
                final NativeObject nameObj = image.asByteArray(MiscUtils.stringToBytes(name));
                final Object[] stat = buildStatArray(file);
                return image.asArrayOfObjects(nameObj, image.asArrayOfObjects(stat));
            }
            return NilObject.SINGLETON;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveClosedir")
    protected abstract static class PrimClosedirNode extends AbstractPrimitiveNode implements Primitive1WithFallback {
        @Specialization(guards = "dirPointer.isByteType()")
        @TruffleBoundary(transferToInterpreterOnException = false)
        protected static final long doClose(@SuppressWarnings("unused") final Object receiver, final NativeObject dirPointer) {
            final long id = ByteBuffer.wrap(dirPointer.getByteStorage()).order(ByteOrder.LITTLE_ENDIAN).getLong();
            final DirStreamEntry entry = OPEN_DIRS.remove(id);
            if (entry != null) {
                try {
                    entry.stream().close();
                } catch (final IOException e) {
                    // ignore
                }
            }
            return 0L;
        }
    }

    private static Object[] buildStatArray(final TruffleFile file) {
        final Object[] values = new Object[13];
        values[0] = NilObject.SINGLETON; // symlink target
        values[1] = getPosixMode(file);
        for (int i = 2; i <= 6; i++) {
            values[i] = 0L; // ino, dev, nlink, uid, gid
        }
        values[7] = safeSize(file);
        values[8] = fileTimeOrZero(file, 'A');
        values[9] = fileTimeOrZero(file, 'M');
        values[10] = fileTimeOrZero(file, 'C');
        values[11] = fileTimeOrZero(file, 'R');
        values[12] = NilObject.SINGLETON; // win32 flags
        return values;
    }

    private static long fileTimeOrZero(final TruffleFile file, final char field) {
        try {
            final BasicFileAttributes attrs = Files.readAttributes(Paths.get(file.getPath()), BasicFileAttributes.class);
            final long millis = switch (field) {
                case 'A' -> attrs.lastAccessTime().toMillis();
                case 'M', 'C' -> attrs.lastModifiedTime().toMillis();
                case 'R' -> attrs.creationTime().toMillis();
                default -> 0L;
            };
            return millis / 1000 + MiscUtils.EPOCH_DELTA_SECONDS;
        } catch (final IOException e) {
            return 0L;
        }
    }

    private static long safeSize(final TruffleFile file) {
        try {
            return file.size();
        } catch (final IOException e) {
            return 0L;
        }
    }

    private static long getPosixMode(final TruffleFile file) {
        long mode = 0;
        if (file.isDirectory()) {
            mode |= 0040000;
        } else {
            mode |= 0100000;
        }
        if (file.isReadable()) {
            mode |= 0444;
        }
        if (file.isWritable()) {
            mode |= 0222;
        }
        if (file.isExecutable()) {
            mode |= 0111;
        }
        return mode;
    }

    @Override
    public List<? extends NodeFactory<? extends AbstractPrimitiveNode>> getFactories() {
        return FileAttributesPluginFactory.getFactories();
    }
}
