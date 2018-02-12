package org.xvm.compiler.ast;


import java.lang.reflect.Field;

import java.util.List;

import org.xvm.asm.ErrorListener;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.compiler.Compiler.Stage;


/**
 * A nullable type expression is a type expression followed by a question mark.
 */
public class NullableTypeExpression
        extends TypeExpression
    {
    // ----- constructors --------------------------------------------------------------------------

    public NullableTypeExpression(TypeExpression type, long lEndPos)
        {
        this.type    = type;
        this.lEndPos = lEndPos;
        }


    // ----- accessors -----------------------------------------------------------------------------

    @Override
    protected boolean canResolveNames()
        {
        return super.canResolveNames() || type.canResolveNames();
        }

    @Override
    public long getStartPosition()
        {
        return type.getStartPosition();
        }

    @Override
    public long getEndPosition()
        {
        return lEndPos;
        }

    @Override
    protected Field[] getChildFields()
        {
        return CHILD_FIELDS;
        }


    // ----- TypeConstant methods ------------------------------------------------------------------

    @Override
    protected TypeConstant instantiateTypeConstant()
        {
        return pool().ensureNullableTypeConstant(type.ensureTypeConstant());
        }


    // ----- compile phases ------------------------------------------------------------------------

    @Override
    public AstNode resolveNames(List<AstNode> listRevisit, ErrorListener errs)
        {
        if (!alreadyReached(Stage.Resolved))
            {
            setStage(Stage.Resolving);

            // resolve the sub-type
            type.resolveNames(listRevisit, errs);

            // obtain and store off the Nullable form of the sub-type
            ensureTypeConstant();
            }

        return super.resolveNames(listRevisit, errs);
        }


    // ----- debugging assistance ------------------------------------------------------------------

    @Override
    public String toString()
        {
        StringBuilder sb = new StringBuilder();

        sb.append(type)
          .append("?");

        return sb.toString();
        }

    @Override
    public String getDumpDesc()
        {
        return toString();
        }


    // ----- fields --------------------------------------------------------------------------------

    protected TypeExpression type;
    protected long           lEndPos;

    private static final Field[] CHILD_FIELDS = fieldsForNames(NullableTypeExpression.class, "type");
    }
