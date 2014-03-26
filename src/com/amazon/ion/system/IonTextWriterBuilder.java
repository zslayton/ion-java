// Copyright (c) 2011-2014 Amazon.com, Inc.  All rights reserved.

package com.amazon.ion.system;

import static com.amazon.ion.system.IonWriterBuilder.InitialIvmHandling.SUPPRESS;

import com.amazon.ion.IonCatalog;
import com.amazon.ion.IonWriter;
import com.amazon.ion.SymbolTable;
import com.amazon.ion.impl._Private_IonTextWriterBuilder;
import com.amazon.ion.impl._Private_Utils;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * The builder for creating {@link IonWriter}s emitting the Ion text syntax.
 * <p>
 * <b>WARNING:</b> This interface should not be implemented or extended by
 * code outside of this library.
 * <p>
 * Builders may be configured once and reused to construct multiple
 * objects.
 * <p>
 * <b>Instances of this class are not not safe for use by multiple threads
 * unless they are {@linkplain #immutable() immutable}.</b>
 * <p>
 * The most general and correct approach is to use the {@link #standard()}
 * builder:
 *<pre>
 *    IonWriter w = IonTextWriterBuilder.standard().build(outputStream);
 *</pre>
 * The standard configuration gives a direct representation of what's written,
 * including version markers and local symbol tables. That's good for
 * diagnostics but it may be more than you want in many situations.
 * In such cases the {@link #minimal()} or {@link #pretty()} builders (or a
 * combination) may give more satisfying output:
 *<pre>
 *    IonWriter w = IonTextWriterBuilder.minimal()
 *                                      .withPrettyPrinting()
 *                                      .build(outputStream);
 *</pre>
 *
 * <p>
 * Configuration properties follow the standard JavaBeans idiom in order to be
 * friendly to dependency injection systems.  They also provide alternative
 * {@code with...()} mutation methods that enable a more fluid style.
 *
 * <h2>Auto-flushing</h2>
 *
 * {@link IonWriter}s created by this builder <em>auto-flush</em> to the
 * underlying data sink after writing each top-level value in the context of
 * the writer.
 * <p>
 * Currently, there is no configuration point available to disable the
 * auto-flushing mechanism. Please vote on
 * <a href="https://jira2.amazon.com/browse/ION-361">JIRA issue ION-361</a>
 * if you require it.
 *
 * @since IonJava R15
 */
public abstract class IonTextWriterBuilder
    extends IonWriterBuilder
{
    /**
     * A strategy for minimizing the output of local symbol tables.
     * By default, no minimization takes place and the writer outputs all data
     * as-is.
     *
     * @since IonJava R16
     */
    public enum LstMinimizing
    {
        /**
         * Discards local symbols, retains imports<!-- and open content-->.
         */
        LOCALS,

        /* TODO Discards local symbols and imports, retains open content.
         * This isn't implemented yet, because our symtab implmentations don't
         * support open content (so it would work the same as EVERYTHING).
         */
//      IMPORTS,

        /**
         * Discards everything, collapsing the LST to an IVM.
         * If {@link com.amazon.ion.system.IonWriterBuilder.IvmMinimizing}
         * is also in effect, then even that IVM may be suppressed.
         *
         * @see IonTextWriterBuilder#setIvmMinimizing(IonWriterBuilder.IvmMinimizing)
         */
        EVERYTHING
    }

    /**
     * The {@code "US-ASCII"} charset.
     */
    public static final Charset ASCII = _Private_Utils.ASCII_CHARSET;

    /**
     * The {@code "UTF-8"} charset.
     */
    public static final Charset UTF8 = _Private_Utils.UTF8_CHARSET;


    /**
     * The standard builder of {@link IonWriter}s, with all configuration
     * properties having their default values. The resulting output is a
     * direct representation of what's written to the writer, including
     * version markers and local symbol tables.
     *
     * @return a new, mutable builder instance.
     *
     * @see #minimal()
     * @see #pretty()
     * @see #json()
     */
    public static IonTextWriterBuilder standard()
    {
        return _Private_IonTextWriterBuilder.standard();
    }

    /**
     * Creates a builder configured to minimize system data, eliminating local
     * symbol tables and minimizing version markers.
     *
     * @return a new, mutable builder instance.
     *
     * @see #withMinimalSystemData()
     *
     * @since IonJava R16
     */
    public static IonTextWriterBuilder minimal()
    {
        return standard().withMinimalSystemData();
    }

    /**
     * Creates a builder preconfigured for basic pretty-printing.
     * <p>
     * The specifics of this configuration may change between releases of this
     * library, so automated processes should not depend on the exact output
     * formatting. In particular, there's currently no promise regarding
     * handling of system data.
     *
     * @return a new, mutable builder instance.
     *
     * @see #withPrettyPrinting()
     */
    public static IonTextWriterBuilder pretty()
    {
        return standard().withPrettyPrinting();
    }

    /**
     * Creates a builder preconfigured for JSON compatibility.
     *
     * @return a new, mutable builder instance.
     *
     * @see #withJsonDowngrade()
     */
    public static IonTextWriterBuilder json()
    {
        return standard().withJsonDowngrade();
    }

    //=========================================================================

    // Config points:
    //   * Default IVM
    //   * Re-use same imports after a finish
    //   * Indentation CharSeq

    private IonCatalog myCatalog;
    private Charset myCharset;
    private InitialIvmHandling myInitialIvmHandling;
    private IvmMinimizing myIvmMinimizing;
    private LstMinimizing myLstMinimizing;
    private SymbolTable[] myImports;
    private int myLongStringThreshold;


    /** NOT FOR APPLICATION USE! */
    protected IonTextWriterBuilder()
    {
    }

    /** NOT FOR APPLICATION USE! */
    protected IonTextWriterBuilder(IonTextWriterBuilder that)
    {
        this.myCatalog              = that.myCatalog;
        this.myCharset              = that.myCharset;
        this.myInitialIvmHandling   = that.myInitialIvmHandling;
        this.myIvmMinimizing        = that.myIvmMinimizing;
        this.myLstMinimizing        = that.myLstMinimizing;
        this.myImports              = that.myImports;
        this.myLongStringThreshold  = that.myLongStringThreshold;
    }


    //=========================================================================

    /**
     * Creates a mutable copy of this builder.
     *
     * @return a new builder with the same configuration as {@code this}.
     */
    public abstract IonTextWriterBuilder copy();

    /**
     * Returns an immutable builder configured exactly like this one.
     *
     * @return this instance, if immutable;
     * otherwise an immutable copy of this instance.
     */
    public abstract IonTextWriterBuilder immutable();

    /**
     * Returns a mutable builder configured exactly like this one.
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     */
    public abstract IonTextWriterBuilder mutable();


    /**
     * NOT FOR APPLICATION USE!
     */
    protected void mutationCheck()
    {
        throw new UnsupportedOperationException("This builder is immutable");
    }


    //=========================================================================

    /**
     * Gets the catalog to use when building an {@link IonWriter}.
     * The catalog is needed to resolve manually-written imports (not common).
     * By default, this property is null.
     *
     * @see #setCatalog(IonCatalog)
     * @see #withCatalog(IonCatalog)
     */
    public final IonCatalog getCatalog()
    {
        return myCatalog;
    }

    /**
     * Sets the catalog to use when building an {@link IonWriter}.
     *
     * @param catalog the catalog to use in built writers.
     *  If null, the writer will be unable to resolve manually-written imports
     *  and may throw an exception.
     *
     * @see #getCatalog()
     * @see #withCatalog(IonCatalog)
     *
     * @throws UnsupportedOperationException if this is immutable.
     */
    public void setCatalog(IonCatalog catalog)
    {
        mutationCheck();
        myCatalog = catalog;
    }

    /**
     * Declares the catalog to use when building an {@link IonWriter},
     * returning a new mutable builder if this is immutable.
     *
     * @param catalog the catalog to use in built writers.
     *  If null, the writer will be unable to resolve manually-written imports
     *  and may throw an exception.
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     *
     * @see #getCatalog()
     * @see #setCatalog(IonCatalog)
     */
    public final IonTextWriterBuilder withCatalog(IonCatalog catalog)
    {
        IonTextWriterBuilder b = mutable();
        b.setCatalog(catalog);
        return b;
    }

    //-------------------------------------------------------------------------

    /**
     * Gets the charset denoting the output encoding.
     * Only ASCII and UTF-8 are supported.
     *
     * @return may be null, denoting the default of UTF-8.
     *
     * @see #setCharset(Charset)
     * @see #withCharset(Charset)
     */
    public final Charset getCharset()
    {
        return myCharset;
    }

    /**
     * Sets the charset denoting the output encoding.
     * Only ASCII and UTF-8 are supported; applications can use the helper
     * constants {@link #ASCII} and {@link #UTF8}.
     *
     * @param charset may be null, denoting the default of UTF-8.
     *
     * @see #getCharset()
     * @see #withCharset(Charset)
     *
     * @throws UnsupportedOperationException if this is immutable.
     */
    public void setCharset(Charset charset)
    {
        mutationCheck();
        if (charset == null
            || charset.equals(ASCII)
            || charset.equals(UTF8))
        {
            myCharset = charset;
        }
        else
        {
            throw new IllegalArgumentException("Unsupported Charset "
                                               + charset);
        }
    }

    /**
     * Declares the charset denoting the output encoding,
     * returning a new mutable builder if this is immutable.
     * Only ASCII and UTF-8 are supported; applications can use the helper
     * constants {@link #ASCII} and {@link #UTF8}.
     *
     * @param charset may be null, denoting the default of UTF-8.
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     *
     * @see #getCharset()
     * @see #setCharset(Charset)
     */
    public final IonTextWriterBuilder withCharset(Charset charset)
    {
        IonTextWriterBuilder b = mutable();
        b.setCharset(charset);
        return b;
    }

    /**
     * Declares the output encoding to be {@code US-ASCII}.
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     */
    public final IonTextWriterBuilder withCharsetAscii()
    {
        return withCharset(ASCII);
    }

    //-------------------------------------------------------------------------

    /**
     * Declares that this builder should minimize system-level output
     * (Ion version markers and local symbol tables).
     * <p>
     * This is equivalent to:
     * <ul>
     *   <li>{@link #setInitialIvmHandling(IonWriterBuilder.InitialIvmHandling)
     *   setInitialIvmHandling}{@code (}{@link IonWriterBuilder.InitialIvmHandling#SUPPRESS SUPPRESS}{@code )}
     *   <li>{@link #setIvmMinimizing(IonWriterBuilder.IvmMinimizing)
     *   setIvmMinimizing}{@code (}{@link IonWriterBuilder.IvmMinimizing#DISTANT DISTANT}{@code )}
     *   <li>{@link #setLstMinimizing(LstMinimizing)
     *   setLstMinimizing}{@code (}{@link LstMinimizing#EVERYTHING EVERYTHING}{@code )}
     * </ul>
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     *
     * @since IonJava R16
     */
    public final IonTextWriterBuilder withMinimalSystemData()
    {
        IonTextWriterBuilder b = mutable();
        b.setInitialIvmHandling(SUPPRESS);
        b.setIvmMinimizing(IvmMinimizing.DISTANT);
        b.setLstMinimizing(LstMinimizing.EVERYTHING);
        return b;
    }


    /**
     * Declares that this builder should use basic pretty-printing.
     * Does not alter the handling of system data.
     * Calling this method alters several other configuration properties,
     * so code should call it first, then make any necessary overrides.
     * <p>
     * The specifics of this configuration may change between releases of this
     * library, so automated processes should not depend on the exact output
     * formatting.
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     */
    public abstract IonTextWriterBuilder withPrettyPrinting();


    /**
     * Declares that this builder should downgrade the writers' output to
     * JSON compatibility. This format cannot round-trip back to Ion with full
     * fidelity.
     * <p>
     * The specific conversions are as follows:
     * <ul>
     *   <li>System data is suppressed per {@link #withMinimalSystemData()}.
     *   <li>All annotations are suppressed.
     *   <li>Nulls of any type are printed as JSON {@code null}.
     *   <li>Blobs are printed as strings, containing Base64.
     *   <li>Clobs are printed as strings, containing only Unicode code points
     *       U+00 through U+FF.
     *   <li>Sexps are printed as lists.
     *   <li>Symbols are printed as strings.
     *   <li>Timestamps are printed as strings, using Ion timestamp format.
     * </ul>
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     */
    public abstract IonTextWriterBuilder withJsonDowngrade();


    //-------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * @return the initial IVM strategy.
     * The default value ({@code null}) indicates that an initial IVM is
     * emitted if and only if it is received by the writer.
     *
     * @see #setInitialIvmHandling(IonWriterBuilder.InitialIvmHandling)
     * @see #withInitialIvmHandling(IonWriterBuilder.InitialIvmHandling)
     */
    @Override
    public final InitialIvmHandling getInitialIvmHandling()
    {
        return myInitialIvmHandling;
    }

    /**
     * Sets the strategy for emitting Ion version markers at the start
     * of the stream. By default, IVMs are emitted only when explicitly
     * written or when necessary (for example, before data that's not Ion 1.0).
     *
     * @param handling the initial IVM strategy.
     * Null indicates that explicitly-written IVMs will be emitted.
     *
     * @see #getInitialIvmHandling()
     * @see #withInitialIvmHandling(IonWriterBuilder.InitialIvmHandling)
     *
     * @throws UnsupportedOperationException if this is immutable.
     */
    public void setInitialIvmHandling(InitialIvmHandling handling)
    {
        mutationCheck();
        myInitialIvmHandling = handling;
    }

    /**
     * Declares the strategy for emitting Ion version markers at the start
     * of the stream, returning a new mutable builder if this is immutable.
     * By default, IVMs are emitted only when explicitly
     * written or when necessary (for example, before data that's not Ion 1.0).
     *
     * @param handling the initial IVM strategy.
     * Null indicates that explicitly-written IVMs will be emitted.
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     *
     * @see #setInitialIvmHandling(IonWriterBuilder.InitialIvmHandling)
     * @see #withInitialIvmHandling(IonWriterBuilder.InitialIvmHandling)
     */
    public final IonTextWriterBuilder
    withInitialIvmHandling(InitialIvmHandling handling)
    {
        IonTextWriterBuilder b = mutable();
        b.setInitialIvmHandling(handling);
        return b;
    }

    //-------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * @return the IVM minimizing strategy.
     * The default value ({@code null}) indicates that no minimization occurs
     * and IVMs are emitted as received by the writer.
     *
     * @see #setIvmMinimizing(IonWriterBuilder.IvmMinimizing)
     * @see #withIvmMinimizing(IonWriterBuilder.IvmMinimizing)
     *
     * @since IonJava R16
     */
    @Override
    public final IvmMinimizing getIvmMinimizing()
    {
        return myIvmMinimizing;
    }

    /**
     * Sets the strategy for reducing or eliminating non-initial Ion version
     * markers. When null, IVMs are emitted as they are written.
     *
     * @param minimizing the IVM minimization strategy.
     * Null indicates that all explicitly-written IVMs will be emitted.
     *
     * @see #getIvmMinimizing()
     * @see #withIvmMinimizing(IonWriterBuilder.IvmMinimizing)
     *
     * @throws UnsupportedOperationException if this is immutable.
     *
     * @since IonJava R16
     */
    public void setIvmMinimizing(IvmMinimizing minimizing)
    {
        mutationCheck();
        myIvmMinimizing = minimizing;
    }

    /**
     * Declares the strategy for reducing or eliminating non-initial Ion version
     * markers, returning a new mutable builder if this is immutable.
     * When null, IVMs are emitted as they are written.
     *
     * @param minimizing the IVM minimization strategy.
     * Null indicates that all explicitly-written IVMs will be emitted.
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     *
     * @see #setIvmMinimizing(IonWriterBuilder.IvmMinimizing)
     * @see #getIvmMinimizing()
     *
     * @since IonJava R16
     */
    public final IonTextWriterBuilder
    withIvmMinimizing(IvmMinimizing minimizing)
    {
        IonTextWriterBuilder b = mutable();
        b.setIvmMinimizing(minimizing);
        return b;
    }

    //-------------------------------------------------------------------------

    /**
     * Gets the strategy for reducing or eliminating local symbol tables.
     * By default, LST data is emitted as received or when necessary
     * (for example, binary data will always collect and emit local symbols).
     *
     * @see #setLstMinimizing(LstMinimizing)
     * @see #withLstMinimizing(LstMinimizing)
     *
     * @since IonJava R16
     */
    public final LstMinimizing getLstMinimizing()
    {
        return myLstMinimizing;
    }

    /**
     * Sets the strategy for reducing or eliminating local symbol tables.
     * By default, LST data is emitted as received or when necessary
     * (for example, binary data will always collect and emit local symbols).
     *
     * @param minimizing the LST minimization strategy.
     * Null indicates that LSTs will be emitted as received.
     *
     * @see #getLstMinimizing()
     * @see #withLstMinimizing(LstMinimizing)
     *
     * @throws UnsupportedOperationException if this is immutable.
     *
     * @since IonJava R16
     */
    public void setLstMinimizing(LstMinimizing minimizing)
    {
        mutationCheck();
        myLstMinimizing = minimizing;
    }

    /**
     * Sets the strategy for reducing or eliminating local symbol tables.
     * By default, LST data is emitted as received or when necessary
     * (for example, binary data will always collect and emit local symbols).
     *
     * @param minimizing the LST minimization strategy.
     * Null indicates that LSTs will be emitted as received.
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     *
     * @see #getLstMinimizing()
     * @see #setLstMinimizing(LstMinimizing)
     *
     * @since IonJava R16
     */
    public final IonTextWriterBuilder
    withLstMinimizing(LstMinimizing minimizing)
    {
        IonTextWriterBuilder b = mutable();
        b.setLstMinimizing(minimizing);
        return b;
    }

    //-------------------------------------------------------------------------

    private static SymbolTable[] safeCopy(SymbolTable[] imports)
    {
        if (imports != null && imports.length != 0)
        {
            imports = imports.clone();
        }
        return imports;
    }

    /**
     * Gets the imports that will be used to construct the initial local
     * symbol table.
     *
     * @return may be null or empty.
     *
     * @see #setImports(SymbolTable...)
     * @see #withImports(SymbolTable...)
     */
    public final SymbolTable[] getImports()
    {
        return safeCopy(myImports);
    }

    /**
     * Sets the shared symbol tables that will be used to construct the
     * initial local symbol table.
     * <p>
     * If the imports sequence is not null and not empty, the output stream
     * will be bootstrapped with a local symbol table that uses the given
     * {@code imports}.
     *
     * @param imports a sequence of shared symbol tables.
     * The first (and only the first) may be a system table.
     *
     * @see #getImports()
     * @see #withImports(SymbolTable...)
     *
     * @throws UnsupportedOperationException if this is immutable.
     */
    public void setImports(SymbolTable... imports)
    {
        mutationCheck();
        myImports = safeCopy(imports);
    }

    /**
     * Declares the imports to use when building an {@link IonWriter},
     * returning a new mutable builder if this is immutable.
     * <p>
     * If the imports sequence is not null and not empty, the output stream
     * will be bootstrapped with a local symbol table that uses the given
     * {@code imports}.
     *
     * @param imports a sequence of shared symbol tables.
     * The first (and only the first) may be a system table.
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     *
     * @see #getImports()
     * @see #setImports(SymbolTable...)
     */
    public final IonTextWriterBuilder withImports(SymbolTable... imports)
    {
        IonTextWriterBuilder b = mutable();
        b.setImports(imports);
        return b;
    }

    //-------------------------------------------------------------------------

    /**
     * Gets the length beyond which string and clob content will be rendered
     * as triple-quoted "long strings".
     * At present, such content will only line-break on extant newlines.
     *
     * @return the threshold for printing triple-quoted strings and clobs.
     * Zero means no limit.
     *
     * @see #setLongStringThreshold(int)
     * @see #withLongStringThreshold(int)
     */
    public final int getLongStringThreshold()
    {
        return myLongStringThreshold;
    }

    /**
     * Sets the length beyond which string and clob content will be rendered
     * as triple-quoted "long strings".
     * At present, such content will only line-break on extant newlines.
     *
     * @param threshold the new threshold; zero means none.
     *
     * @see #getLongStringThreshold()
     * @see #withLongStringThreshold(int)
     *
     * @throws UnsupportedOperationException if this is immutable.
     */
    public void setLongStringThreshold(int threshold)
    {
        mutationCheck();
        myLongStringThreshold = threshold;
    }

    /**
     * Declares the length beyond which string and clob content will be rendered
     * as triple-quoted "long strings".
     * At present, such content will only line-break on extant newlines.
     *
     * @param threshold the new threshold; zero means none.
     *
     * @see #getLongStringThreshold()
     * @see #setLongStringThreshold(int)
     *
     * @return this instance, if mutable;
     * otherwise a mutable copy of this instance.
     */
    public final IonTextWriterBuilder withLongStringThreshold(int threshold)
    {
        IonTextWriterBuilder b = mutable();
        b.setLongStringThreshold(threshold);
        return b;
    }


    //=========================================================================


    /**
     * Creates a new writer that will write text to the given output
     * stream.
     * <p>
     * If you have an {@link OutputStream}, you'll get better performance using
     * {@link #build(OutputStream)} as opposed to wrapping your stream in an
     * {@link Appendable} and calling this method.
     *
     * @param out the stream that will receive Ion text data.
     * Must not be null.
     *
     * @return a new {@link IonWriter} instance; not {@code null}.
     */
    public abstract IonWriter build(Appendable out);


    /**
     * Creates a new writer that will write text to the given output
     * stream.
     *
     * @param out the stream that will receive Ion text data.
     * Must not be null.
     *
     * @return a new {@link IonWriter} instance; not {@code null}.
     */
    public abstract IonWriter build(OutputStream out);
}
