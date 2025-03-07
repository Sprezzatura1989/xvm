package org.xvm.asm.constants;


import java.io.DataOutput;
import java.io.IOException;

import java.util.function.Consumer;

import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool;


/**
 * Represent a constant that will eventually be replaced with a real identity constant.
 */
public class UnresolvedNameConstant
        extends PseudoConstant
        implements ResolvableConstant
    {
    // ----- constructors --------------------------------------------------------------------------

    /**
     * Construct a place-holder constant that will eventually be replaced with a real constant.
     *
     * Note, that outside of "equals" and "toString" implementations, the information held by this
     * constant is not used at all. The resolution logic (see
     * {@link org.xvm.compiler.ast.NamedTypeExpression#resolveNames) will use its own state to
     * {@link #resolve(Constant) resolve} it.
     *
     * @param pool  the ConstantPool that this Constant should belong to, even though it will never
     *              contain it while it's unresolved and will immediately replace it as it becomes
     *              resolved
     */
    public UnresolvedNameConstant(ConstantPool pool, String[] names, boolean fExplicitlyNonNarrowing)
        {
        super(pool);

        m_asName    = names;
        m_fNoNarrow = fExplicitlyNonNarrowing;
        }


    // ----- type-specific functionality -----------------------------------------------------------

    /**
     * @return the name of the constant
     */
    public String getName()
        {
        if (m_constId instanceof IdentityConstant)
            {
            return ((IdentityConstant) m_constId).getName();
            }

        String[]      names = m_asName;
        StringBuilder sb    = new StringBuilder();
        for (int i = 0, c = names.length; i < c; ++i)
            {
            if (i > 0)
                {
                sb.append('.');
                }
            sb.append(names[i]);
            }
        return sb.toString();
        }

    /**
     * @return true if the UnresolvedNameConstant has been resolved
     */
    public boolean isNameResolved()
        {
        return m_constId != null;
        }


    // ----- ResolvableConstant methods ------------------------------------------------------------

    @Override
    public Constant getResolvedConstant()
        {
        return m_constId;
        }

    @Override
    public void resolve(Constant constant)
        {
        assert m_constId == null || m_constId == constant || m_constId.equals(constant);
        assert !(constant instanceof TypeConstant);
        m_constId = constant;
        }


    // ----- Constant methods ----------------------------------------------------------------------

    @Override
    public Format getFormat()
        {
        return isNameResolved()
                ? m_constId.getFormat()
                : Format.UnresolvedName;
        }

    @Override
    public boolean isValueCacheable()
        {
        return isNameResolved() && m_constId.isValueCacheable();
        }

    @Override
    public boolean isClass()
        {
        return isNameResolved() && m_constId.isClass();
        }

    @Override
    public boolean isAutoNarrowing()
        {
        return isNameResolved() && m_constId.isAutoNarrowing();
        }

    @Override
    public boolean isProperty()
        {
        return isNameResolved() && m_constId.isProperty();
        }

    @Override
    public boolean containsUnresolved()
        {
        return true;
        }

    @Override
    public void forEachUnderlying(Consumer<Constant> visitor)
        {
        if (isNameResolved())
            {
            visitor.accept(m_constId);
            }
        }

    @Override
    public Constant resolveTypedefs()
        {
        return m_constId == null
                ? this
                : m_constId.resolveTypedefs();
        }

    @Override
    protected void setPosition(int iPos)
        {
        throw new UnsupportedOperationException("unresolved: " + this);
        }

    @Override
    protected Object getLocator()
        {
        if (isNameResolved())
            {
            Constant constId = unwrap();
            if (constId instanceof IdentityConstant)
                {
                return ((IdentityConstant) m_constId).getLocator();
                }
            else if (constId instanceof PseudoConstant)
                {
                return ((PseudoConstant) m_constId).getLocator();
                }
            }
        return null;
        }

    @Override
    public String getValueString()
        {
        return isNameResolved()
                ? m_constId.getValueString()
                : (getName() + (m_fNoNarrow ? "!" : ""));
        }

    @Override
    protected int compareDetails(Constant that)
        {
        if (that instanceof ResolvableConstant)
            {
            that = ((ResolvableConstant) that).unwrap();
            }

        if (isNameResolved())
            {
            return unwrap().compareTo(that);
            }

        if (that instanceof UnresolvedNameConstant)
            {
            String[] asThis = m_asName;
            String[] asThat = ((UnresolvedNameConstant) that).m_asName;
            int      cThis  = asThis.length;
            int      cThat  = asThat.length;
            for (int i = 0, c = Math.min(cThis, cThat); i < c; ++i)
                {
                int n = asThis[i].compareTo(asThat[i]);
                if (n != 0)
                    {
                    return n;
                    }
                }
            int n = cThis - cThat;
            if (n == 0)
                {
                n = (m_fNoNarrow ? 1 : 0) - (((UnresolvedNameConstant) that).m_fNoNarrow ? 1 : 0);
                }
            return n;
            }

        // need to return a value that allows for stable sorts, but unless this==that, the
        // details can never be equal
        return this == that ? 0 : -1;
        }


    // ----- XvmStructure methods ------------------------------------------------------------------

    @Override
    protected void registerConstants(ConstantPool pool)
        {
        if (isNameResolved())
            {
            m_constId = pool.register(m_constId);
            }
        }

    @Override
    protected void assemble(DataOutput out)
            throws IOException
        {
        Constant constId = unwrap();
        if (constId instanceof IdentityConstant)
            {
            ((IdentityConstant) constId).assemble(out);
            }
        else if (constId instanceof PseudoConstant)
            {
            ((PseudoConstant) constId).assemble(out);
            }
        else
            {
            throw new IllegalStateException("unresolved: " + getName());
            }
        }

    @Override
    public String getDescription()
        {
        return isNameResolved()
                ? "(resolved) " + m_constId.getDescription()
                : "name=" + getName();
        }

    // ----- Object methods ------------------------------------------------------------------------

    @Override
    public int hashCode()
        {
        if (isNameResolved())
            {
            return m_constId.hashCode();
            }
        else
            {
            int      nHash = 0;
            String[] names = m_asName;
            for (int i = 0, c = names.length; i < c; ++i)
                {
                nHash ^= names[i].hashCode();
                }
            return nHash;
            }
        }


    // ----- fields --------------------------------------------------------------------------------

    /**
     * The unresolved name, as an array of simple names.
     */
    private String[] m_asName;

    /**
     * The resolved constant, or null if the name has not yet been resolved to a constant.
     */
    private Constant m_constId;

    /**
     * True iff the type name is explicitly non-narrowing.
     */
    private boolean m_fNoNarrow;
    }
