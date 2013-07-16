// Copyright (c) 2010-2013 Amazon.com, Inc.  All rights reserved.

package com.amazon.ion.impl;

import static com.amazon.ion.SystemSymbols.SYMBOLS;
import static com.amazon.ion.impl._Private_IonConstants.tidList;
import static com.amazon.ion.impl._Private_IonConstants.tidSexp;
import static com.amazon.ion.impl._Private_IonConstants.tidStruct;

import com.amazon.ion.Decimal;
import com.amazon.ion.IonException;
import com.amazon.ion.IonReader;
import com.amazon.ion.IonType;
import com.amazon.ion.SymbolTable;
import com.amazon.ion.SymbolToken;
import com.amazon.ion.Timestamp;
import com.amazon.ion.impl.Base64Encoder.TextStream;
import com.amazon.ion.impl.IonBinary.BufferManager;
import com.amazon.ion.system.IonTextWriterBuilder.LstMinimizing;
import com.amazon.ion.util.IonTextUtils;
import com.amazon.ion.util.IonTextUtils.SymbolVariant;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.CharBuffer;

/**
 *
 */
final class IonWriterSystemText
    extends IonWriterSystem
{
    /** Not null. */
    private final _Private_IonTextWriterBuilder _options;
    /** At least one. */
    private final int _long_string_threshold;

    private final _Private_IonTextAppender _output;

    BufferManager _manager;

    /** Ensure we don't use a closed {@link #output} stream. */
    private boolean _closed;
    /** Flags if current container to be written is struct enforcing writing of member names */
    boolean     _in_struct;
    boolean     _pending_separator;

    /**
     * True when the last data written was a triple-quoted string, meaning we
     * cannot write another long string lest it be incorrectly concatenated.
     */
    private boolean _following_long_string;

    int         _separator_character;

    int         _top;
    int []      _stack_parent_type = new int[10];
    boolean[]   _stack_pending_comma = new boolean[10];

    /**
     * Creates a writer to an {@link OutputStream}.
     *
     * @throws NullPointerException if any parameter is null.
     */
    protected IonWriterSystemText(SymbolTable defaultSystemSymtab,
                                  _Private_IonTextWriterBuilder options,
                                  OutputStream out)
    {
        this(defaultSystemSymtab,
             options,
             new OutputStreamIonTextAppender(out, options.getCharset()));
    }

    /**
     * Creates a write to an {@link Appendable}.
     *
     * @throws NullPointerException if any parameter is null.
     */
    protected IonWriterSystemText(SymbolTable defaultSystemSymtab,
                                  _Private_IonTextWriterBuilder options,
                                  Appendable out)
    {
        this(defaultSystemSymtab,
             options,
             new AppendableIonTextAppender(out, options.getCharset()));
    }

    /**
     * @throws NullPointerException if any parameter is null.
     */
    protected IonWriterSystemText(SymbolTable defaultSystemSymtab,
                                  _Private_IonTextWriterBuilder options,
                                  _Private_IonTextAppender out)
    {
        super(defaultSystemSymtab,
              options.getInitialIvmHandling(),
              options.getIvmMinimizing());

        _output = out;
        _options = options;

        if (_options.isPrettyPrintOn()) {
            _separator_character = '\n';
        }
        else {
            _separator_character = ' ';
        }

        int threshold = _options.getLongStringThreshold();
        if (threshold < 1) threshold = Integer.MAX_VALUE;
        _long_string_threshold = threshold;
    }


    _Private_IonTextWriterBuilder getBuilder()
    {
        return _options;
    }

    @Override
    public int getDepth()
    {
        return _top;
    }
    public boolean isInStruct() {
        return _in_struct;
    }
    protected IonType getContainer()
    {
        IonType container;

        if (_top < 1) {
            container = IonType.DATAGRAM;
        }
        else {
            switch(_stack_parent_type[_top-1]) {
            case _Private_IonConstants.tidDATAGRAM:
                container = IonType.DATAGRAM;
                break;
            case _Private_IonConstants.tidSexp:
                container = IonType.SEXP;
                break;
            case _Private_IonConstants.tidList:
                container = IonType.LIST;
                break;
            case _Private_IonConstants.tidStruct:
                container = IonType.STRUCT;
                break;
            default:
                throw new IonException("unexpected container in parent stack: "+_stack_parent_type[_top-1]);
            }
        }
        return container;
    }
    void push(int typeid)
    {
        if (_top + 1 == _stack_parent_type.length) {
            growStack();
        }
        _stack_parent_type[_top] = typeid;
        _stack_pending_comma[_top] = _pending_separator;
        switch (typeid) {
        case _Private_IonConstants.tidSexp:
            _separator_character = ' ';
            break;
        case _Private_IonConstants.tidList:
        case _Private_IonConstants.tidStruct:
            _separator_character = ',';
            break;
        default:
            _separator_character = _options.isPrettyPrintOn() ? '\n' : ' ';
        break;
        }
        _top++;
    }
    void growStack() {
        int oldlen = _stack_parent_type.length;
        int newlen = oldlen * 2;
        int[] temp1 = new int[newlen];
        boolean[] temp3 = new boolean[newlen];

        System.arraycopy(_stack_parent_type, 0, temp1, 0, oldlen);
        System.arraycopy(_stack_pending_comma, 0, temp3, 0, oldlen);

        _stack_parent_type = temp1;
        _stack_pending_comma = temp3;
    }
    int pop() {
        _top--;
        int typeid = _stack_parent_type[_top];  // popped parent

        int parentid = (_top > 0) ? _stack_parent_type[_top - 1] : -1;
        switch (parentid) {
        case -1:
        case _Private_IonConstants.tidSexp:
            _in_struct = false;
            _separator_character = ' ';
            break;
        case _Private_IonConstants.tidList:
            _in_struct = false;
            _separator_character = ',';
            break;
        case _Private_IonConstants.tidStruct:
            _in_struct = true;
            _separator_character = ',';
            break;
        default:
            _separator_character = _options.isPrettyPrintOn() ? '\n' : ' ';
        break;
        }

        return typeid;
    }

    /**
     * @return a tid
     * @throws ArrayIndexOutOfBoundsException if _top < 1
     */
    int topType() {
        return _stack_parent_type[_top - 1];
    }

    boolean topPendingComma() {
        if (_top == 0) return false;
        return _stack_pending_comma[_top - 1];
    }

    private boolean containerIsSexp()
    {
        if (_top == 0) return false;
        int topType = topType();
        return (topType == tidSexp);
    }

    void printLeadingWhiteSpace() throws IOException {
        for (int ii=0; ii<_top; ii++) {
            _output.appendAscii(' ');
            _output.appendAscii(' ');
        }
    }
    void closeCollection(char closeChar) throws IOException {
       if (_options.isPrettyPrintOn()) {
           _output.appendAscii(_options.lineSeparator());
           printLeadingWhiteSpace();
       }
       _output.appendAscii(closeChar);
    }


    private void writeSidLiteral(int sid)
        throws IOException
    {
        assert sid > 0;

        // No extra handling needed for JSON strings, this is already legal.

        boolean asString = _options._symbol_as_string;
        if (asString) _output.appendAscii('"');

        _output.appendAscii('$');
        // TODO optimize to avoid intermediate string
        _output.appendAscii(Integer.toString(sid));

        if (asString) _output.appendAscii('"');
    }


    /**
     * @param value must not be null.
     */
    private void writeSymbolToken(String value) throws IOException
    {
        if (_options._symbol_as_string)
        {
            if (_options._string_as_json)
            {
                _output.printJsonString(value);
            }
            else
            {
                _output.printString(value);
            }
        }
        else
        {
            SymbolVariant variant = IonTextUtils.symbolVariant(value);
            switch (variant)
            {
                case IDENTIFIER:
                {
                    _output.appendAscii(value);
                    break;
                }
                case OPERATOR:
                {
                    if (containerIsSexp())
                    {
                        _output.appendAscii(value);
                        break;
                    }
                    // else fall through...
                }
                case QUOTED:
                {
                    _output.printQuotedSymbol(value);
                    break;
                }
            }
        }
    }


    @Override
    void startValue() throws IOException
    {
        super.startValue();

        boolean followingLongString = _following_long_string;

        if (_options.isPrettyPrintOn()) {
            if (_pending_separator && _separator_character > ' ') {
                // Only bother if the separator is non-whitespace.
                _output.appendAscii((char)_separator_character);
                followingLongString = false;
            }
            _output.appendAscii(_options.lineSeparator());
            printLeadingWhiteSpace();
        }
        else if (_pending_separator) {
            _output.appendAscii((char)_separator_character);
            if (_separator_character > ' ') followingLongString = false;
        }

        // write field name
        if (_in_struct) {
            SymbolToken sym = assumeFieldNameSymbol();
            String name = sym.getText();
            if (name == null) {
                int sid = sym.getSid();
                writeSidLiteral(sid);
            }
            else {
                writeSymbolToken(name);
            }
            _output.appendAscii(':');
            clearFieldName();
            followingLongString = false;
        }

        // write annotations
        if (hasAnnotations()) {
            if (! _options._skip_annotations) {
                SymbolToken[] annotations = getTypeAnnotationSymbols();
                for (SymbolToken ann : annotations) {
                    String name = ann.getText();
                    if (name == null) {
                        _output.appendAscii('$');
                        _output.appendAscii(Integer.toString(ann.getSid()));
                    }
                    else {
                        _output.printSymbol(name);
                    }
                    _output.appendAscii("::");
                }
                followingLongString = false;
            }
            clearAnnotations();
        }

        _following_long_string = followingLongString;
    }

    void closeValue() {
        super.endValue();
        _pending_separator = true;
        _following_long_string = false;  // Caller overwrites this as needed.
    }



    @Override
    void writeIonVersionMarkerAsIs(SymbolTable systemSymtab)
        throws IOException
    {
        writeSymbolAsIs(systemSymtab.getIonVersionId());
    }

    @Override
    void writeLocalSymtab(SymbolTable symtab)
        throws IOException
    {
        SymbolTable[] imports = symtab.getImportedTables();

        LstMinimizing min = _options.getLstMinimizing();
        if (min == null)
        {
            symtab.writeTo(this);
        }
        else if (min == LstMinimizing.LOCALS && imports.length > 0)
        {
            // Copy the symtab, but filter out local symbols.

            IonReader reader = new UnifiedSymbolTableReader(symtab);

            // move onto and write the struct header
            IonType t = reader.next();
            assert(IonType.STRUCT.equals(t));
            SymbolToken[] a = reader.getTypeAnnotationSymbols();
            // you (should) always have the $ion_symbol_table annotation
            assert(a != null && a.length >= 1);

            // now we'll start a local symbol table struct
            // in the underlying system writer
            setTypeAnnotationSymbols(a);
            stepIn(IonType.STRUCT);

            // step into the symbol table struct and
            // write the values - EXCEPT the symbols field
            reader.stepIn();
            for (;;) {
                t = reader.next();
                if (t == null) break;
                // get the field name and skip over 'symbols'
                String name = reader.getFieldName();
                if (SYMBOLS.equals(name)) {
                    continue;
                }
                writeValue(reader);
            }

            // we're done step out and move along
            stepOut();
        }
        else  // Collapse to IVM
        {
            SymbolTable systemSymtab = symtab.getSystemSymbolTable();
            writeIonVersionMarker(systemSymtab);
        }

        super.writeLocalSymtab(symtab);
    }


    public void stepIn(IonType containerType) throws IOException
    {
        startValue();

        int tid;
        char opener;
        switch (containerType)
        {
            case SEXP:
                if (!_options._sexp_as_list) {
                    tid = tidSexp;
                    _in_struct = false;
                    opener = '('; break;
                }
                // else fall through and act just like list
            case LIST:
                tid = tidList;
                _in_struct = false;
                opener = '[';
                break;
            case STRUCT:
                tid = tidStruct;
                _in_struct = true;
                opener = '{';
                break;
            default:
                throw new IllegalArgumentException();
        }

        push(tid);
        _output.appendAscii(opener);
        _pending_separator = false;
        _following_long_string = false;
    }

    public void stepOut() throws IOException
    {
        if (_top < 1) {
            throw new IllegalStateException(IonMessages.CANNOT_STEP_OUT);
        }
        _pending_separator = topPendingComma();
        int tid = pop();

        char closer;
        switch (tid)
        {
            case tidList:   closer = ']'; break;
            case tidSexp:   closer = ')'; break;
            case tidStruct: closer = '}'; break;
            default:
                throw new IllegalStateException();
        }
        closeCollection(closer);
        closeValue();
    }


    //========================================================================


    @Override
    public void writeNull()
        throws IOException
    {
        startValue();
        _output.appendAscii("null");
        closeValue();
    }
    public void writeNull(IonType type) throws IOException
    {
        startValue();

        String nullimage;

        if (_options._untyped_nulls)
        {
            nullimage = "null";
        }
        else
        {
            switch (type) {
                case NULL:      nullimage = "null";           break;
                case BOOL:      nullimage = "null.bool";      break;
                case INT:       nullimage = "null.int";       break;
                case FLOAT:     nullimage = "null.float";     break;
                case DECIMAL:   nullimage = "null.decimal";   break;
                case TIMESTAMP: nullimage = "null.timestamp"; break;
                case SYMBOL:    nullimage = "null.symbol";    break;
                case STRING:    nullimage = "null.string";    break;
                case BLOB:      nullimage = "null.blob";      break;
                case CLOB:      nullimage = "null.clob";      break;
                case SEXP:      nullimage = "null.sexp";      break;
                case LIST:      nullimage = "null.list";      break;
                case STRUCT:    nullimage = "null.struct";    break;

                default: throw new IllegalStateException("unexpected type " + type);
            }
        }

        _output.appendAscii(nullimage);
        closeValue();
    }
    public void writeBool(boolean value)
        throws IOException
    {
        startValue();
        _output.appendAscii(value ? "true" : "false");
        closeValue();
    }

    // this will convert long to a char array in @_integerBuffer back to front
    // and return the starting position in the array
    char[] _fixedIntBuffer = new char[_Private_IonConstants.MAX_LONG_TEXT_SIZE];
    int longToChar(long value)
    {
        int j = _fixedIntBuffer.length - 1;
        if (value == 0) {
            _fixedIntBuffer[j--] = '0';
        } else {
            if (value < 0) {
                while (value != 0) {
                    _fixedIntBuffer[j--] = (char)(0x30 - value % 10);
                    value /= 10;
                }
                _fixedIntBuffer[j--] = '-';
            } else {
                while (value != 0) {
                    _fixedIntBuffer[j--] = (char)(0x30 + value % 10);
                    value /= 10;
                }
            }
        }
        return j + 1;
    }

    public void writeInt(int value)
        throws IOException
    {
        startValue();
        int start = longToChar(value);
        // using CharBuffer to avoid copying of _fixedIntBuffer in String
        _output.appendAscii(CharBuffer.wrap(_fixedIntBuffer), start, _fixedIntBuffer.length);
        closeValue();
    }

    public void writeInt(long value)
        throws IOException
    {
        startValue();
        int start = longToChar(value);
        // using CharBuffer to avoid copying of _fixedIntBuffer in String
        _output.appendAscii(CharBuffer.wrap(_fixedIntBuffer), start, _fixedIntBuffer.length);
        closeValue();
    }

    public void writeInt(BigInteger value) throws IOException
    {
        if (value == null) {
            writeNull(IonType.INT);
            return;
        }

        startValue();
        _output.appendAscii(value.toString());
        closeValue();
    }

    public void writeFloat(double value)
        throws IOException
    {
        startValue();

        // shortcut zero cases
        if (value == 0.0) {
            if (Double.compare(value, 0d) == 0) {
                // positive zero
                _output.appendAscii("0e0");
            }
            else {
                // negative zero
                _output.appendAscii("-0e0");
            }
        }
        else if (Double.isNaN(value)) {
            _output.appendAscii("nan");
        }
        else if (Double.isInfinite(value)) {
            if (value > 0) {
                _output.appendAscii("+inf");
            }
            else {
                _output.appendAscii("-inf");
            }
        }
        else {
            String str = Double.toString(value);
            if (str.indexOf('E') == -1) {
                str += "e0";
            }
            _output.appendAscii(str);
        }

        closeValue();
    }


    @Override
    public void writeDecimal(BigDecimal value) throws IOException
    {
        if (value == null) {
            writeNull(IonType.DECIMAL);
            return;
        }

        startValue();
        BigDecimal decimal = value;
        BigInteger unscaled = decimal.unscaledValue();

        int signum = decimal.signum();
        if (signum < 0)
        {
            _output.appendAscii('-');
            unscaled = unscaled.negate();
        }
        else if (decimal instanceof Decimal
             && ((Decimal)decimal).isNegativeZero())
        {
            // for the various forms of negative zero we have to
            // write the sign ourselves, since neither BigInteger
            // nor BigDecimal recognize negative zero, but Ion does.
            _output.appendAscii('-');
        }

        final String unscaledText = unscaled.toString();
        final int significantDigits = unscaledText.length();

        final int scale = decimal.scale();
        final int exponent = -scale;

        if (_options._decimal_as_float)
        {
            _output.appendAscii(unscaledText);
            _output.appendAscii('e');
            _output.appendAscii(Integer.toString(exponent));
        }
        else if (exponent == 0)
        {
            _output.appendAscii(unscaledText);
            _output.appendAscii('.');
        }
        else if (0 < scale)
        {
            int wholeDigits;
            int remainingScale;
            if (significantDigits > scale)
            {
                wholeDigits = significantDigits - scale;
                remainingScale = 0;
            }
            else
            {
                wholeDigits = 1;
                remainingScale = scale - significantDigits + 1;
            }

            _output.appendAscii(unscaledText, 0, wholeDigits);
            if (wholeDigits < significantDigits)
            {
                _output.appendAscii('.');
                _output.appendAscii(unscaledText, wholeDigits,
                             significantDigits);
            }

            if (remainingScale != 0)
            {
                _output.appendAscii("d-");
                _output.appendAscii(Integer.toString(remainingScale));
            }
        }
        else // (exponent > 0)
        {
            // We cannot move the decimal point to the right, adding
            // rightmost zeros, because that would alter the precision.
            _output.appendAscii(unscaledText);
            _output.appendAscii('d');
            _output.appendAscii(Integer.toString(exponent));
        }
        closeValue();
    }

    public void writeTimestamp(Timestamp value) throws IOException
    {
        if (value == null) {
            writeNull(IonType.TIMESTAMP);
            return;
        }

        startValue();

        if (_options._timestamp_as_millis)
        {
            long millis = value.getMillis();
            _output.appendAscii(Long.toString(millis));
        }
        else if (_options._timestamp_as_string)
        {
            // Timestamp is ASCII-safe so this is easy
            _output.appendAscii('"');
            _output.appendAscii(value.toString());
            _output.appendAscii('"');
        }
        else
        {
            _output.appendAscii(value.toString());
        }

        closeValue();
    }

    public void writeString(String value)
        throws IOException
    {
        startValue();
        if (value != null
            && ! _following_long_string
            && _long_string_threshold < value.length())
        {
            // TODO This can lead to mixed newlines in the output.
            // It assumes NL line separators, but _options could use CR+NL
            _output.printLongString(value);

            // This sets _following_long_string = false so we must overwrite
            closeValue();
            _following_long_string = true;
        }
        else
        {
            if (_options._string_as_json)
            {
                _output.printJsonString(value);
            }
            else
            {
                _output.printString(value);
            }
            closeValue();
        }
    }

    // escape sequences for character below ascii 32 (space)
    static final String [] LOW_ESCAPE_SEQUENCES = {
          "\\0",   "\\x01", "\\x02", "\\x03",
          "\\x04", "\\x05", "\\x06", "\\a",
          "\\b",   "\\t",   "\\n",   "\\v",
          "\\f",   "\\r",   "\\x0e", "\\x0f",
          "\\x10", "\\x11", "\\x12", "\\x13",
          "\\x14", "\\x15", "\\x16", "\\x17",
          "\\x18", "\\x19", "\\x1a", "\\x1b",
          "\\x1c", "\\x1d", "\\x1e", "\\x1f",
    };

    @Override
    void writeSymbolAsIs(int symbolId)
        throws IOException
    {
        SymbolTable symtab = getSymbolTable();
        String text = symtab.findKnownSymbol(symbolId);
        if (text != null)
        {
            writeSymbolAsIs(text);
        }
        else
        {
            startValue();
            writeSidLiteral(symbolId);
            closeValue();
        }
    }

    @Override
    public void writeSymbolAsIs(String value)
        throws IOException
    {
        if (value == null)
        {
            writeNull(IonType.SYMBOL);
            return;
        }

        startValue();
        writeSymbolToken(value);
        closeValue();
    }

    public void writeBlob(byte[] value, int start, int len)
        throws IOException
    {
        if (value == null)
        {
            writeNull(IonType.BLOB);
            return;
        }

        TextStream ts = new TextStream(new ByteArrayInputStream(value, start, len));

        // base64 encoding is 6 bits per char so
        // it evens out at 3 bytes in 4 characters
        char[] buf = new char[_options.isPrettyPrintOn() ? 80 : 400];
        CharBuffer cb = CharBuffer.wrap(buf);

        startValue();

        if (_options._blob_as_string) {
            _output.appendAscii('"');
        }
        else {
            _output.appendAscii("{{");
            if (_options.isPrettyPrintOn()) {
                _output.appendAscii(' ');
            }
        }

        for (;;) {
            // TODO is it better to fill up the CharBuffer before outputting?
            int clen = ts.read(buf, 0, buf.length);
            if (clen < 1) break;
            _output.appendAscii(cb, 0, clen);
        }


        if (_options._blob_as_string) {
            _output.appendAscii('"');
        }
        else {
            if (_options.isPrettyPrintOn()) {
                _output.appendAscii(' ');
            }
            _output.appendAscii("}}");
        }
        closeValue();
    }

    public void writeClob(byte[] value, int start, int len)
        throws IOException
    {
        if (value == null)
        {
            writeNull(IonType.CLOB);
            return;
        }

        startValue();

        final boolean json =
            _options._clob_as_string && _options._string_as_json;

        final boolean longString = (_long_string_threshold < value.length);

        if (!_options._clob_as_string) {
            _output.appendAscii("{{");
            if (_options.isPrettyPrintOn()) {
                _output.appendAscii(" ");
            }
        }

        if (json) {
            _output.printJsonClob(value, start, start + len);
        } else if (longString) {
            _output.printLongClob(value, start, start + len);
        } else {
            _output.printClob(value, start, start + len);
        }

        if (! _options._clob_as_string) {
            if (_options.isPrettyPrintOn()) {
                _output.appendAscii(" ");
            }
            _output.appendAscii("}}");
        }

        closeValue();
    }


    /**
     * {@inheritDoc}
     * <p>
     * The {@link OutputStream} spec is mum regarding the behavior of flush on
     * a closed stream, so we shouldn't assume that our stream can handle that.
     */
    public void flush() throws IOException
    {
        if (! _closed) {
            ((Flushable)_output).flush();
        }
    }

    public void close() throws IOException
    {
        if (! _closed) {
            try
            {
                if (getDepth() == 0) {
                    finish();
                }
            }
            finally
            {
                // Do this first so we are closed even if the call below throws.
                _closed = true;

                ((Closeable)_output).close();
            }
        }
    }
}

