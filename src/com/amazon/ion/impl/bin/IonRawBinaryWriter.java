// Copyright (c) 2015 Amazon.com, Inc.  All rights reserved.

package com.amazon.ion.impl.bin;

import static com.amazon.ion.Decimal.isNegativeZero;
import static com.amazon.ion.IonType.BLOB;
import static com.amazon.ion.IonType.BOOL;
import static com.amazon.ion.IonType.CLOB;
import static com.amazon.ion.IonType.DECIMAL;
import static com.amazon.ion.IonType.FLOAT;
import static com.amazon.ion.IonType.INT;
import static com.amazon.ion.IonType.LIST;
import static com.amazon.ion.IonType.NULL;
import static com.amazon.ion.IonType.SEXP;
import static com.amazon.ion.IonType.STRING;
import static com.amazon.ion.IonType.STRUCT;
import static com.amazon.ion.IonType.SYMBOL;
import static com.amazon.ion.IonType.TIMESTAMP;
import static com.amazon.ion.IonType.isContainer;
import static com.amazon.ion.Timestamp.Precision.DAY;
import static com.amazon.ion.Timestamp.Precision.FRACTION;
import static com.amazon.ion.Timestamp.Precision.MINUTE;
import static com.amazon.ion.Timestamp.Precision.MONTH;
import static com.amazon.ion.Timestamp.Precision.SECOND;
import static java.lang.Double.doubleToRawLongBits;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableMap;

import com.amazon.ion.IonCatalog;
import com.amazon.ion.IonException;
import com.amazon.ion.IonType;
import com.amazon.ion.IonWriter;
import com.amazon.ion.SymbolTable;
import com.amazon.ion.SymbolToken;
import com.amazon.ion.Timestamp;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Low-level binary {@link IonWriter} that understands encoding concerns but doesn't operate with any sense of symbol table management.
 */
/*package*/ final class IonRawBinaryWriter extends AbstractIonWriter
{
    /** short-hand for array of bytes--useful for static definitions. */
    private static byte[] bytes(int... vals) {
        final byte[] octets = new byte[vals.length];

        for (int i = 0; i < vals.length; i++) {
            octets[i] = (byte) vals[i];
        }

        return octets;
    }

    private static final byte[] IVM = bytes(0xE0, 0x01, 0x00, 0xEA);

    private static Map<IonType, Byte> generateTypedNullMap() {
        final Map<IonType, Byte> nulls = new EnumMap<IonType, Byte>(IonType.class);

        nulls.put(NULL,      (byte) 0x0F);
        nulls.put(BOOL,      (byte) 0x1F);
        nulls.put(INT,       (byte) 0x2F);
        nulls.put(FLOAT,     (byte) 0x4F);
        nulls.put(DECIMAL,   (byte) 0x5F);
        nulls.put(TIMESTAMP, (byte) 0x6F);
        nulls.put(SYMBOL,    (byte) 0x7F);
        nulls.put(STRING,    (byte) 0x8F);
        nulls.put(CLOB,      (byte) 0x9F);
        nulls.put(BLOB,      (byte) 0xAF);
        nulls.put(LIST,      (byte) 0xBF);
        nulls.put(SEXP,      (byte) 0xCF);
        nulls.put(STRUCT,    (byte) 0xDF);

        return unmodifiableMap(nulls);
    }
    private static final Map<IonType, Byte> NULLS = generateTypedNullMap();
    private static final byte NULL_NULL = NULLS.get(NULL);

    private static final byte BOOL_FALSE        = (byte) 0x10;
    private static final byte BOOL_TRUE         = (byte) 0x11;

    private static final byte INT_ZERO          = (byte) 0x20;

    private static final byte POS_INT_TYPE      = (byte) 0x20;
    private static final byte NEG_INT_TYPE      = (byte) 0x30;
    private static final byte FLOAT_TYPE        = (byte) 0x48;

    private static final byte DECIMAL_TYPE      = (byte) 0x50;
    private static final byte TIMESTAMP_TYPE    = (byte) 0x60;
    private static final byte SYMBOL_TYPE       = (byte) 0x70;
    private static final byte STRING_TYPE       = (byte) 0x80;

    private static final byte CLOB_TYPE         = (byte) 0x90;
    private static final byte BLOB_TYPE         = (byte) 0xA0;

    private static final byte DECIMAL_POS_ZERO               = (byte) 0x50;
    private static final byte DECIMAL_NEGATIVE_ZERO_MANTISSA = (byte) 0x80;

    private static final BigInteger BIG_INT_LONG_MAX_VALUE = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger BIG_INT_LONG_MIN_VALUE = BigInteger.valueOf(Long.MIN_VALUE);

    private static final byte VARINT_NEG_ZERO   = (byte) 0xC0;

    private static final byte[] makeTypedPreallocatedBytes(final int typeDesc, final int length)
    {
        final byte[] bytes = new byte[length];
        bytes[0]          = (byte) typeDesc;
        if (length > 1)
        {
            bytes[length - 1] = (byte) 0x80;
        }
        return bytes;
    }

    private static Map<IonType, byte[]> makeContainerTypedPreallocatedMap(final int length) {
        final Map<IonType, byte[]> extendedSizes = new EnumMap<IonType, byte[]>(IonType.class);

        extendedSizes.put(LIST,      makeTypedPreallocatedBytes(0xBE, length));
        extendedSizes.put(SEXP,      makeTypedPreallocatedBytes(0xCE, length));
        extendedSizes.put(STRUCT,    makeTypedPreallocatedBytes(0xDE, length));

        return unmodifiableMap(extendedSizes);
    }

    /**
     * Determines how container/container-like values should be padded
     */
    /*package*/ enum PreallocationMode
    {
        /** Allocate no length.  (forces side patching) */
        PREALLOCATE_0(0x0000,   1)
        {
            @Override
            /*package*/ void patchLength(final WriteBuffer buffer, final long position, final long lengthValue)
            {
                throw new IllegalStateException("Cannot patch in PREALLOCATE 0 mode");
            }
        },

        /** Preallocate 2 bytes of length. */
        PREALLOCATE_1(0x007F,   2)
        {
            @Override
            /*package*/ void patchLength(final WriteBuffer buffer, long position, long lengthValue)
            {
                buffer.writeVarUIntDirect1At(position, lengthValue);
            }
        },

        /** Preallocate 2 bytes of length. */
        PREALLOCATE_2(0x3FFF,   3)
        {
            @Override
            /*package*/ void patchLength(final WriteBuffer buffer, long position, long lengthValue)
            {
                buffer.writeVarUIntDirect2At(position, lengthValue);
            }
        }
        ;

        private final int                       contentMaxLength;
        private final int                       typedLength;
        private final Map<IonType, byte[]>      containerTypedPreallocatedBytes;
        private final byte[]                    annotationsTypedPreallocatedBytes;

        private PreallocationMode(final int contentMaxLength, final int typedLength)
        {
            this.contentMaxLength = contentMaxLength;
            this.typedLength = typedLength;
            this.containerTypedPreallocatedBytes   = makeContainerTypedPreallocatedMap(typedLength);
            this.annotationsTypedPreallocatedBytes = makeTypedPreallocatedBytes(0xEE, typedLength);
        }

        /*package*/ abstract void patchLength(final WriteBuffer buffer, final long position, final long length);

        /*package*/ static PreallocationMode withPadSize(final int pad)
        {
            switch (pad)
            {
                case 0:
                    return PreallocationMode.PREALLOCATE_0;
                case 1:
                    return PreallocationMode.PREALLOCATE_1;
                case 2:
                    return PreallocationMode.PREALLOCATE_2;
            }
            throw new IllegalArgumentException("No such preallocation mode for: " + pad);
        }
    }

    private static final byte STRING_TYPE_EXTENDED_LENGTH       = (byte) 0x8E;
    private static final byte[] STRING_TYPED_PREALLOCATED_2     = makeTypedPreallocatedBytes(0x8E, 2);
    private static final byte[] STRING_TYPED_PREALLOCATED_3     = makeTypedPreallocatedBytes(0x8E, 3);

    /** Max supported annotation length specifier size supported. */
    private static final int MAX_ANNOTATION_LENGTH = 0x7F;

    private enum ContainerType
    {
        SEQUENCE(true),
        STRUCT(true),
        VALUE(false),
        ANNOTATION(false);

        public final boolean allowedInStepOut;

        private ContainerType(final boolean allowedInStepOut)
        {
            this.allowedInStepOut = allowedInStepOut;
        }
    }

    private static class ContainerInfo
    {
        /** Whether or not the container is a struct */
        public final ContainerType type;
        /** The location of the pre-allocated size descriptor in the buffer. */
        public final long position;
        /** The size of the current value. */
        public long length;

        public ContainerInfo(final ContainerType type, final long offset)
        {
            this.type = type;
            this.position = offset;
        }

        @Override
        public String toString()
        {
            return "(CI " + type + " pos:" + position + " len:" + length + ")";
        }
    }

    private static class PatchPoint
    {
        /** position of the patch buffer where the length data is stored. */
        public final long position;
        /** length of the data to be patched in.*/
        public final int newLength;
        /** length of the data being patched out.*/
        public final int oldLength;

        public PatchPoint(final long position, final int newLength, final int oldLength)
        {
            this.position = position;
            this.newLength = newLength;
            this.oldLength = oldLength;
        }
    }

    /*package*/ enum StreamCloseMode
    {
        NO_CLOSE,
        CLOSE
    }

    /*package*/ enum StreamFlushMode
    {
        NO_FLUSH,
        FLUSH
    }

    private final BlockAllocator                allocator;
    private final OutputStream                  out;
    private final StreamCloseMode               streamCloseMode;
    private final StreamFlushMode               streamFlushMode;
    private final PreallocationMode             preallocationMode;
    private final WriteBuffer                   buffer;
    private final WriteBuffer                   patchBuffer;
    private final SortedMap<Long, PatchPoint>   patchPoints;
    private final List<ContainerInfo>           containers;
    private int                                 depth;
    private boolean                             hasWrittenValuesSinceFinished;
    private boolean                             hasWrittenValuesSinceConstructed;

    private SymbolToken                 currentFieldName;
    private final List<SymbolToken>     currentAnnotations;

    private boolean                     closed;

    /*package*/ IonRawBinaryWriter(final BlockAllocatorProvider provider,
                                   final int blockSize,
                                   final OutputStream out,
                                   final StreamCloseMode streamCloseMode,
                                   final StreamFlushMode streamFlushMode,
                                   final PreallocationMode preallocationMode)
                                   throws IOException
    {
        if (out == null) { throw new NullPointerException(); }

        this.allocator = provider.vendAllocator(blockSize);
        this.out = out;
        this.streamCloseMode = streamCloseMode;
        this.streamFlushMode = streamFlushMode;
        this.preallocationMode = preallocationMode;
        this.buffer = new WriteBuffer(allocator);
        this.patchBuffer = new WriteBuffer(allocator);
        this.patchPoints = new TreeMap<Long, PatchPoint>();
        this.containers = new ArrayList<ContainerInfo>(16);

        this.depth                = 0;
        this.hasWrittenValuesSinceFinished     = false;
        this.hasWrittenValuesSinceConstructed = false;

        this.currentFieldName = null;
        this.currentAnnotations = new ArrayList<SymbolToken>();

        this.closed = false;
    }

    /** Always returns {@link Symbols#systemSymbolTable()}. */
    public SymbolTable getSymbolTable()
    {
        return Symbols.systemSymbolTable();
    }

    // Current Value Meta

    public void setFieldName(final String name)
    {
        throw new UnsupportedOperationException("Cannot set field name on a low-level binary writer via string");
    }

    public void setFieldNameSymbol(final SymbolToken name)
    {
        if (!isInStruct())
        {
            throw new IonException("Cannot set field name outside of struct context");
        }
        currentFieldName = name;
    }

    public void setTypeAnnotations(final String... annotations)
    {
        throw new UnsupportedOperationException("Cannot set annotations on a low-level binary writer via string");
    }

    public void setTypeAnnotationSymbols(final SymbolToken... annotations)
    {
        currentAnnotations.clear();
        if (annotations != null)
        {
            currentAnnotations.addAll(asList(annotations));
        }
    }

    public void addTypeAnnotation(final String annotation)
    {
        throw new UnsupportedOperationException("Cannot add annotations on a low-level binary writer via string");
    }

    // Additional Current State Meta

    /*package*/ void addTypeAnnotationSymbol(final SymbolToken annotation)
    {
        currentAnnotations.add(annotation);
    }

    /*package*/ boolean hasAnnotations()
    {
        return !currentAnnotations.isEmpty();
    }

    /** Returns true if a value has been written since construction or {@link #finish()}. */
    /*package*/ boolean hasWrittenValuesSinceFinished()
    {
        return hasWrittenValuesSinceFinished;
    }

    /** Returns true if a value has been written since the writer was constructed. */
    /*package*/ boolean hasWrittenValuesSinceConstructed()
    {
        return hasWrittenValuesSinceConstructed;
    }

    /*package*/ boolean hasAnnotation(final int sid)
    {
        for (final SymbolToken token : currentAnnotations)
        {
            if (sid == token.getSid())
            {
                return true;
            }
        }
        return false;
    }

    /*package*/ int getFieldId()
    {
        return currentFieldName.getSid();
    }

    // Compatibility with Implementation Writer Interface

    public IonCatalog getCatalog()
    {
        throw new UnsupportedOperationException();
    }

    public boolean isFieldNameSet()
    {
        return currentFieldName != null;
    }

    public void writeIonVersionMarker() throws IOException
    {
        buffer.writeBytes(IVM);
    }

    public int getDepth()
    {
        return depth;
    }

    // Low-Level Writing

    private void updateLength(long length)
    {
        if (containers.isEmpty())
        {
            return;
        }

        containers.get(containers.size() - 1).length += length;
    }

    private void pushContainer(final ContainerType type)
    {
        // XXX we push before writing the type of container
        containers.add(new ContainerInfo(type, buffer.position() + 1));
    }

    private ContainerInfo currentContainer()
    {
        return containers.isEmpty() ? null : containers.get(containers.size() - 1);
    }

    private void addPatchPoint(final long position, final int oldLength, final long value)
    {
        // record the size in a patch buffer
        final long patchPosition = patchBuffer.position();
        final int patchLength = patchBuffer.writeVarUInt(value);
        patchPoints.put(position, new PatchPoint(patchPosition, patchLength, oldLength));
        updateLength(patchLength - oldLength);
    }

    private ContainerInfo popContainer()
    {
        final ContainerInfo current = currentContainer();
        if (current == null)
        {
            throw new IllegalStateException("Tried to pop container state without said container");
        }
        containers.remove(containers.size() - 1);

        // only patch for real containers and annotations -- we use VALUE for tracking only
        final long length = current.length;
        if (current.type != ContainerType.VALUE)
        {
            // patch in the length
            final long position = current.position;
            if (current.length <= preallocationMode.contentMaxLength && preallocationMode != PreallocationMode.PREALLOCATE_0)
            {
                preallocationMode.patchLength(buffer, position, length);
            }
            else
            {
                // side patch
                if (current.length <= 0xD && preallocationMode == PreallocationMode.PREALLOCATE_0)
                {
                    // XXX if we're not using padding we can get here and optimize the length a little without side patching!
                    final long typePosition = position - 1;
                    final long type = (buffer.getUInt8At(typePosition) & 0xF0) | current.length;
                    buffer.writeUInt8At(typePosition, type);
                }
                else
                {
                    addPatchPoint(position, preallocationMode.typedLength - 1, length);
                }
            }
        }
        // make sure to record length upward
        updateLength(length);
        return current;
    }

    private void writeVarUInt(final long value)
    {
        if (value < 0)
        {
            throw new IonException("Cannot write negative value as unsigned");
        }
        final int len = buffer.writeVarUInt(value);
        updateLength(len);
    }

    private void writeVarInt(final long value)
    {
        final int len = buffer.writeVarInt(value);
        updateLength(len);
    }

    private static int checkSid(SymbolToken symbol)
    {
        final int sid = symbol.getSid();
        if (sid < 1)
        {
            throw new IllegalArgumentException("Invalid symbol: " + symbol.getText() + " SID: " + sid);
        }
        return sid;
    }

    /** prepare to write values with field name and annotations. */
    private void prepareValue()
    {
        if (isInStruct() && currentFieldName == null)
        {
            throw new IllegalStateException("IonWriter.setFieldName() must be called before writing a value into a struct.");
        }
        if (currentFieldName != null)
        {
            writeVarUInt(checkSid(currentFieldName));

            // clear out field name
            currentFieldName = null;
        }
        if (!currentAnnotations.isEmpty())
        {
            // we have to push a container context for annotations
            updateLength(preallocationMode.typedLength);
            pushContainer(ContainerType.ANNOTATION);
            buffer.writeBytes(preallocationMode.annotationsTypedPreallocatedBytes);

            final long annotationsLengthPosition = buffer.position();
            buffer.writeVarUInt(0L);
            int annotationsLength = 0;
            for (final SymbolToken symbol : currentAnnotations)
            {
                final int sid = checkSid(symbol);
                final int symbolLength = buffer.writeVarUInt(sid);
                annotationsLength += symbolLength;
            }
            if (annotationsLength > MAX_ANNOTATION_LENGTH)
            {
                // TODO deal with side patching if we want to support > 32 4-byte symbols annotations... seems excessive
                throw new IonException("Annotations too large: " + currentAnnotations);
            }

            // update the annotations size
            updateLength(/*length specifier*/ 1 + annotationsLength);
            // patch the annotations length
            buffer.writeVarUIntDirect1At(annotationsLengthPosition, annotationsLength);

            // clear out annotations
            currentAnnotations.clear();
        }
    }

    /** Closes out annotations. */
    private void finishValue()
    {
        final ContainerInfo current = currentContainer();
        if (current != null && current.type == ContainerType.ANNOTATION)
        {
            // close out and patch the length
            popContainer();
        }
        hasWrittenValuesSinceFinished = true;
        hasWrittenValuesSinceConstructed = true;
    }

    // Container Manipulation

    public void stepIn(final IonType containerType) throws IOException
    {
        if (!isContainer(containerType))
        {
            throw new IonException("Cannot step into " + containerType);
        }
        prepareValue();
        updateLength(preallocationMode.typedLength);
        pushContainer(containerType == STRUCT ? ContainerType.STRUCT : ContainerType.SEQUENCE);
        depth++;
        buffer.writeBytes(preallocationMode.containerTypedPreallocatedBytes.get(containerType));
    }

    public void stepOut() throws IOException
    {
        if (currentFieldName != null)
        {
            throw new IonException("Cannot step out with field name set");
        }
        if (!currentAnnotations.isEmpty())
        {
            throw new IonException("Cannot step out with field name set");
        }
        final ContainerInfo container = currentContainer();
        if (container == null || !container.type.allowedInStepOut)
        {
            throw new IonException("Cannot step out when not in container");
        }
        // close out the container
        popContainer();
        depth--;
        // close out the annotations if any
        finishValue();
    }

    public boolean isInStruct()
    {
        return !containers.isEmpty() && currentContainer().type == ContainerType.STRUCT;
    }

    // Write Value Methods

    public void writeNull() throws IOException
    {
        prepareValue();
        updateLength(1);
        buffer.writeByte(NULL_NULL);
        finishValue();
    }

    public void writeNull(final IonType type) throws IOException
    {
        prepareValue();
        updateLength(1);
        buffer.writeByte(NULLS.get(type));
        finishValue();
    }

    public void writeBool(final boolean value) throws IOException
    {
        prepareValue();
        updateLength(1);
        if (value)
        {
            buffer.writeByte(BOOL_TRUE);
        }
        else
        {
            buffer.writeByte(BOOL_FALSE);
        }
        finishValue();
    }

    /**
     * Writes a type descriptor followed by unsigned integer value.
     * Does not check for sign.
     * Note that this does not do {@link #prepareValue()} or {@link #finishValue()}.
     */
    private void writeTypedUInt(final int type, final long value)
    {
        if (value <= 0xFFL)
        {
            updateLength(2);
            buffer.writeUInt8(type | 0x01);
            buffer.writeUInt8(value);
        }
        else if (value <= 0xFFFFL)
        {
            updateLength(3);
            buffer.writeUInt8(type | 0x02);
            buffer.writeUInt16(value);
        }
        else if (value <= 0xFFFFFFL)
        {
            updateLength(4);
            buffer.writeUInt8(type | 0x03);
            buffer.writeUInt24(value);
        }
        else if (value <= 0xFFFFFFFFL)
        {
            updateLength(5);
            buffer.writeUInt8(type | 0x04);
            buffer.writeUInt32(value);
        }
        else if (value <= 0xFFFFFFFFFFL)
        {
            updateLength(6);
            buffer.writeUInt8(type | 0x05);
            buffer.writeUInt40(value);
        }
        else if (value <= 0xFFFFFFFFFFFFL)
        {
            updateLength(7);
            buffer.writeUInt8(type | 0x06);
            buffer.writeUInt48(value);
        }
        else if (value <= 0xFFFFFFFFFFFFFFL)
        {
            updateLength(8);
            buffer.writeUInt8(type | 0x07);
            buffer.writeUInt56(value);
        }
        else
        {
            updateLength(9);
            buffer.writeUInt8(type | 0x08);
            buffer.writeUInt64(value);
        }
    }

    public void writeInt(long value) throws IOException
    {
        prepareValue();
        if (value == 0)
        {
            updateLength(1);
            buffer.writeByte(INT_ZERO);
        }
        else
        {
            int type = POS_INT_TYPE;
            if (value < 0)
            {
                type = NEG_INT_TYPE;
                if (value == Long.MIN_VALUE)
                {
                    // XXX special case for min_value which will not play nice with signed arithmetic and fit into the positive space
                    // XXX we keep 2's complement of Long.MIN_VALUE because it encodes to unsigned 2 ** 63 (0x8000000000000000L)
                    // XXX WriteBuffer.writeUInt64() never looks at sign
                    updateLength(9);
                    buffer.writeUInt8(NEG_INT_TYPE | 0x8);
                    buffer.writeUInt64(value);
                }
                else
                {
                    // get the magnitude, sign is already encoded
                    value = -value;
                    writeTypedUInt(type, value);
                }
            }
            else
            {
                writeTypedUInt(type, value);
            }
        }
        finishValue();
    }

    /** Write a raw byte array as some type. Note that this does not do {@link #prepareValue()}. */
    private void writeTypedBytes(final int type, final byte[] data, final int offset, final int length)
    {
        int totalLength = 1 + length;
        if (length < 14)
        {
            buffer.writeUInt8(type | length);
        }
        else
        {
            // need to specify length explicitly
            buffer.writeUInt8(type | 0xE);
            final int sizeLength = buffer.writeVarUInt(length);
            totalLength += sizeLength;
        }
        updateLength(totalLength);
        buffer.writeBytes(data, offset, length);
    }

    public void writeInt(BigInteger value) throws IOException
    {
        if (value == null)
        {
            writeNull(IonType.INT);
            return;
        }
        if (value.compareTo(BIG_INT_LONG_MIN_VALUE) >= 0 && value.compareTo(BIG_INT_LONG_MAX_VALUE) <= 0)
        {
            // for the small stuff, just write it as a signed int64
            writeInt(value.longValue());
            return;
        }

        prepareValue();

        int type = POS_INT_TYPE;
        if(value.signum() < 0)
        {
            type = NEG_INT_TYPE;
            value = value.negate();
        }

        // generate big-endian representation of the positive value
        final byte[] magnitude = value.toByteArray();
        writeTypedBytes(type, magnitude, 0, magnitude.length);

        finishValue();
    }

    public void writeFloat(final double value) throws IOException
    {
        prepareValue();
        updateLength(9);
        buffer.writeUInt8(FLOAT_TYPE);
        buffer.writeUInt64(doubleToRawLongBits(value));
        finishValue();
    }

    /** Encodes a decimal, updating the current container length context (which is probably a Decimal/Timestamp). */
    private void writeDecimalValue(final BigDecimal value)
    {
        final boolean isNegZero = isNegativeZero(value);
        final int signum = value.signum();
        final int exponent = -value.scale();

        writeVarInt(exponent);

        final BigInteger mantissaBigInt = value.unscaledValue();
        if (mantissaBigInt.compareTo(BIG_INT_LONG_MIN_VALUE) >= 0 && mantissaBigInt.compareTo(BIG_INT_LONG_MAX_VALUE) <= 0)
        {
            // we can fit into the long space
            final long mantissa = mantissaBigInt.longValue();
            if (signum == 0 && !isNegZero)
            {
                // positive zero does not need to be encoded
            }
            else if (isNegZero)
            {
                // XXX special case for negative zero, we have to encode as a signed zero in the Int format
                updateLength(1);
                buffer.writeByte(DECIMAL_NEGATIVE_ZERO_MANTISSA);
            }
            else if (mantissa == Long.MIN_VALUE)
            {
                // XXX special case for min value -- we need 64-bits to store the magnitude and we need a bit for sign
                updateLength(9);
                buffer.writeUInt8(0x80);
                buffer.writeUInt64(mantissa);
            }
            else if (mantissa >= 0xFFFFFFFFFFFFFF81L && mantissa <= 0x000000000000007FL)
            {
                updateLength(1);
                buffer.writeInt8(mantissa);
            }
            else if (mantissa >= 0xFFFFFFFFFFFF8001L && mantissa <= 0x0000000000007FFFL)
            {
                updateLength(2);
                buffer.writeInt16(mantissa);
            }
            else if (mantissa >= 0xFFFFFFFFFF800001L && mantissa <= 0x00000000007FFFFFL)
            {
                updateLength(3);
                buffer.writeInt24(mantissa);
            }
            else if (mantissa >= 0xFFFFFFFF80000001L && mantissa <= 0x000000007FFFFFFFL)
            {
                updateLength(4);
                buffer.writeInt32(mantissa);
            }
            else if (mantissa >= 0xFFFFFF8000000001L && mantissa <= 0x0000007FFFFFFFFFL)
            {
                updateLength(5);
                buffer.writeInt40(mantissa);
            }
            else if (mantissa >= 0xFFFF800000000001L && mantissa <= 0x00007FFFFFFFFFFFL)
            {
                updateLength(6);
                buffer.writeInt48(mantissa);
            }
            else if (mantissa >= 0xFF80000000000001L && mantissa <= 0x007FFFFFFFFFFFFFL)
            {
                updateLength(7);
                buffer.writeInt56(mantissa);
            }
            else
            {
                // TODO consider being more space efficient for integers that can be written with 6/7 bytes.
                updateLength(8);
                buffer.writeInt64(mantissa);
            }
        }
        else
        {
            final BigInteger magnitude = signum > 0 ? mantissaBigInt : mantissaBigInt.negate();
            final byte[] bits = magnitude.toByteArray();
            if (signum < 0)
            {
                if ((bits[0] & 0x80) == 0)
                {
                    bits[0] |= 0x80;
                }
                else
                {
                    // not enough space in the bits to store the negative sign
                    updateLength(1);
                    buffer.writeUInt8(0x80);
                }
            }
            updateLength(bits.length);
            buffer.writeBytes(bits);
        }
    }

    private void patchSingleByteTypedOptimisticValue(final byte type, final ContainerInfo info)
    {
        if (info.length <= 0xD)
        {
            // we fit -- overwrite the type byte
            buffer.writeUInt8At(info.position - 1, type | info.length);
        }
        else
        {
            // side patch
            buffer.writeUInt8At(info.position - 1, type | 0xE);
            addPatchPoint(info.position, 0, info.length);
        }
    }

    public void writeDecimal(final BigDecimal value) throws IOException
    {
        if (value == null)
        {
            writeNull(IonType.DECIMAL);
            return;
        }

        prepareValue();

        if (value.signum() == 0 && value.scale() == 0 && !isNegativeZero(value))
        {
            // 0d0 can be written in one byte
            updateLength(1);
            buffer.writeUInt8(DECIMAL_POS_ZERO);
        }
        else
        {
            // optimistically try to fit decimal length in low nibble (most should)
            updateLength(1);
            pushContainer(ContainerType.VALUE);
            buffer.writeByte(DECIMAL_TYPE);
            writeDecimalValue(value);
            final ContainerInfo info = popContainer();
            patchSingleByteTypedOptimisticValue(DECIMAL_TYPE, info);
        }

        finishValue();
    }

    @SuppressWarnings("deprecation")
    public void writeTimestamp(final Timestamp value) throws IOException
    {
        if (value == null)
        {
            writeNull(IonType.TIMESTAMP);
            return;
        }
        prepareValue();

        // optimistically try to fit a timestamp length in low nibble (most should)
        updateLength(1);
        pushContainer(ContainerType.VALUE);
        buffer.writeByte(TIMESTAMP_TYPE);

        // OFFSET
        final Integer offset = value.getLocalOffset();
        if (offset == null)
        {
            // special case for unknown -00:00
            updateLength(1);
            buffer.writeByte(VARINT_NEG_ZERO);
        }
        else
        {
            writeVarInt(offset.intValue());
        }

        // YEAR
        final int year = value.getZYear();
        writeVarUInt(year);

        // XXX it is really convenient to rely on the ordinal
        final int precision = value.getPrecision().ordinal();

        if (precision >= MONTH.ordinal())
        {
            final int month = value.getZMonth();
            writeVarUInt(month);
        }
        if (precision >= DAY.ordinal())
        {
            final int day = value.getZDay();
            writeVarUInt(day);
        }
        if (precision >= MINUTE.ordinal())
        {
            final int hour = value.getZHour();
            writeVarUInt(hour);
            final int minute = value.getZMinute();
            writeVarUInt(minute);
        }
        if (precision >= SECOND.ordinal())
        {
            final int second = value.getZSecond();
            writeVarUInt(second);
        }
        if (precision >= FRACTION.ordinal())
        {
            final BigDecimal fraction = value.getZFractionalSecond();
            writeDecimalValue(fraction);
        }

        final ContainerInfo info = popContainer();
        patchSingleByteTypedOptimisticValue(TIMESTAMP_TYPE, info);

        finishValue();
    }

    public void writeSymbol(String content) throws IOException
    {
        throw new UnsupportedOperationException("Symbol writing via string is not supported in low-level binary writer");
    }

    public void writeSymbolToken(final SymbolToken content) throws IOException
    {
        if (content == null)
        {
            writeNull(IonType.SYMBOL);
            return;
        }
        final int sid = checkSid(content);
        prepareValue();
        writeTypedUInt(SYMBOL_TYPE, sid);
        finishValue();
    }

    public void writeString(final String value) throws IOException
    {
        if (value == null)
        {
            writeNull(IonType.STRING);
            return;
        }
        prepareValue();

        // assume the string is ASCII and round up the sizing -- we should revisit this for CJK heavy use cases
        int estUtf8Length = value.length();
        int preallocatedLength = 1;
        final long lengthPosition = buffer.position() + 1;
        if (estUtf8Length <= 0xD)
        {
            // size fits in low nibble
            estUtf8Length = 0xD;
            buffer.writeUInt8(STRING_TYPE);
        }
        else
        {
            if (estUtf8Length <= 0x7F)
            {
                estUtf8Length = 0x7F;
                preallocatedLength = 2;
                buffer.writeBytes(STRING_TYPED_PREALLOCATED_2);
            }
            else
            {
                estUtf8Length = 0x3FFF;
                preallocatedLength = 3;
                buffer.writeBytes(STRING_TYPED_PREALLOCATED_3);
            }
            // TODO decide if it is worth preallocating for > 16KB strings
        }
        updateLength(preallocatedLength);

        // actually encode the string
        final int utf8Length = buffer.writeUTF8(value);
        if (utf8Length <= estUtf8Length)
        {
            // we fit!
            if (utf8Length <= 0xD)
            {
                // special case for patching the type byte itself with the length
                buffer.writeUInt8At(lengthPosition - 1, STRING_TYPE | utf8Length);
            }
            else if (utf8Length <= 0x7F)
            {
                buffer.writeVarUIntDirect1At(lengthPosition, utf8Length);
            }
            else
            {
                buffer.writeVarUIntDirect2At(lengthPosition, utf8Length);
            }
        }
        else
        {
            // side patch
            if (estUtf8Length == 0xD)
            {
                // we need to patch the type with the extended length
                buffer.writeUInt8At(lengthPosition - 1, STRING_TYPE_EXTENDED_LENGTH);
            }
            addPatchPoint(lengthPosition, preallocatedLength - 1, utf8Length);
        }

        updateLength(utf8Length);

        finishValue();
    }

    public void writeClob(byte[] data) throws IOException
    {
        if (data == null)
        {
            writeNull(IonType.CLOB);
            return;
        }
        writeClob(data, 0, data.length);
    }

    public void writeClob(final byte[] data, final int offset, final int length) throws IOException
    {
        if (data == null)
        {
            writeNull(IonType.CLOB);
            return;
        }
        prepareValue();
        writeTypedBytes(CLOB_TYPE, data, offset, length);
        finishValue();
    }

    public void writeBlob(byte[] data) throws IOException
    {
        if (data == null)
        {
            writeNull(IonType.BLOB);
            return;
        }
        writeBlob(data, 0, data.length);
    }

    public void writeBlob(final byte[] data, final int offset, final int length) throws IOException
    {
        if (data == null)
        {
            writeNull(IonType.BLOB);
            return;
        }
        prepareValue();
        writeTypedBytes(BLOB_TYPE, data, offset, length);
        finishValue();
    }

    // Stream Manipulation/Terminators

    /*package*/ long position()
    {
        return buffer.position();
    }

    /*package*/ void truncate(long position)
    {
        buffer.truncate(position);
        final SortedMap<Long, PatchPoint> patches = patchPoints.tailMap(position);
        if (!patches.isEmpty())
        {
            // truncate the patch buffer and patch points
            final PatchPoint first = patches.values().iterator().next();
            patchBuffer.truncate(first.position);
            patches.clear();
        }
    }

    public void flush() throws IOException {}

    public void finish() throws IOException
    {
        if (!containers.isEmpty())
        {
            throw new IllegalStateException("Cannot finish within container: " + containers);
        }

        if (patchPoints.isEmpty())
        {
            // nothing to patch--write 'em out!
            buffer.writeTo(out);
        }
        else
        {
            long bufferPosition = 0;
            for (final Entry<Long, PatchPoint> patchEntry : patchPoints.entrySet())
            {
                final long bufferPatchPosition = patchEntry.getKey();
                final PatchPoint patch = patchEntry.getValue();

                // write up to the thing to be patched
                final long bufferLength = bufferPatchPosition - bufferPosition;
                buffer.writeTo(out, bufferPosition, bufferLength);

                // write out the patch
                patchBuffer.writeTo(out, patch.position, patch.newLength);

                // skip over the preallocated varuint field
                bufferPosition = bufferPatchPosition;
                bufferPosition += patch.oldLength;
            }
            buffer.writeTo(out, bufferPosition, buffer.position() - bufferPosition);
        }
        patchPoints.clear();
        patchBuffer.reset();
        buffer.reset();

        if (streamFlushMode == StreamFlushMode.FLUSH)
        {
            out.flush();
        }

        hasWrittenValuesSinceFinished = false;
    }

    public void close() throws IOException
    {
        if (closed)
        {
            return;
        }
        closed = true;
        try
        {
            try
            {
                finish();
            }
            catch (final IllegalStateException e)
            {
                // callers don't expect this...
            }

            // release all of our blocks -- these should never throw
            buffer.close();
            patchBuffer.close();
            allocator.close();
        }
        finally
        {
            if (streamCloseMode == StreamCloseMode.CLOSE)
            {
                // release the stream
                out.close();
            }
        }
    }
}
