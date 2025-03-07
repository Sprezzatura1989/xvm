package org.xvm.compiler.ast;


import java.lang.reflect.Field;

import org.xvm.asm.ConstantPool;
import org.xvm.asm.ErrorListener;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.compiler.Constants;

import org.xvm.util.Severity;


/**
 * An annotated type expression is a type expression preceded with an annotation.
 */
public class AnnotatedTypeExpression
        extends TypeExpression
    {
    // ----- constructors --------------------------------------------------------------------------

    public AnnotatedTypeExpression(Annotation annotation, TypeExpression type)
        {
        this.annotation = annotation;
        this.type       = type;
        }


    // ----- accessors -----------------------------------------------------------------------------

    /**
     * @return the annotation
     */
    Annotation getAnnotation()
        {
        return annotation;
        }

    /**
     * @return true iff this AnnotatedTypeExpression has been instructed to disassociate its
     *         annotation from the underlying type, which will occur if the annotation needs to be
     *         associated with property, method, or variable, for example
     */
    public boolean isDisassociated()
        {
        return m_fDisassociateAnnotation;
        }

    /**
     * This method allows a VariableDeclarationStatement (for example) to steal the annotation from
     * the type expression, if the annotation applies to the variable and not the type. One example
     * would be "@Inject Int i;", in which case the "Int" is not annotated (it is the variable "i"
     * that is annotated).
     *
     * @return the annotation
     */
    public Annotation disassociateAnnotation()
        {
        m_fDisassociateAnnotation = true;
        resetTypeConstant();
        return annotation;
        }

    @Override
    protected boolean canResolveNames()
        {
        return super.canResolveNames() || type.canResolveNames();
        }

    @Override
    public long getStartPosition()
        {
        return annotation.getStartPosition();
        }

    @Override
    public long getEndPosition()
        {
        return type.getEndPosition();
        }

    @Override
    protected Field[] getChildFields()
        {
        return CHILD_FIELDS;
        }

    @Override
    public boolean isIntroductoryType()
        {
        return true;
        }

    @Override
    public TypeExpression unwrapIntroductoryType()
        {
        return type;
        }

    @Override
    public void replaceIntroducedType(TypeExpression type)
        {
        this.type = type;
        type.setParent(this);
        }


    // ----- TypeExpression methods ----------------------------------------------------------------

    @Override
    protected TypeConstant instantiateTypeConstant(Context ctx)
        {
        // this is a bit complicated:
        // 1) we need the class of the annotation, which is resolved during validateContent()
        // 2) we need a constant for each parameter, but those are only guaranteed to be correct
        //    after validateContent()
        // 3) a "dis-associated" annotation is one that does not apply to the underlying type, so
        //    the underlying type is unchanged by this AnnotatedTypeExpression
        ConstantPool pool           = pool();
        TypeConstant typeUnderlying = type.ensureTypeConstant(ctx);
        return isDisassociated()
                ? typeUnderlying    // our annotation is not added to the underlying type constant
                : pool.ensureAnnotatedTypeConstant(typeUnderlying, annotation.ensureAnnotation(pool));
        }

    @Override
    protected void collectAnonInnerClassInfo(AnonInnerClass info)
        {
        info.addAnnotation(getAnnotation());
        type.collectAnonInnerClassInfo(info);
        }


    // ----- Expression methods --------------------------------------------------------------------

    @Override
    protected Expression validate(Context ctx, TypeConstant typeRequired,
            ErrorListener errs)
        {
        boolean        fValid  = true;
        ConstantPool   pool    = pool();
        TypeExpression typeNew = (TypeExpression) type.validate(ctx, pool().typeType(), errs);
        if (typeNew == null)
            {
            fValid = false;
            }
        else
            {
            type = typeNew;
            }

        TypeConstant typeReferent  = ensureTypeConstant(ctx);
        TypeConstant typeReference = pool.ensureParameterizedTypeConstant(pool.typeType(), typeReferent);

        if (fValid)
            {
            // the annotation must mix in to the Var (if it's disassociated), or into the underlying
            // type otherwise
            Annotation             annoAst  = getAnnotation();
            org.xvm.asm.Annotation annoAsm  = annoAst.ensureAnnotation(pool);
            TypeConstant           typeAnno = annoAsm.getAnnotationType();
            if (!  (isDisassociated() && typeAnno.getExplicitClassInto().isIntoVariableType()
                || !isDisassociated() && typeReferent.isA(typeAnno.ensureTypeInfo(errs).getInto())))
                {
                annoAst.log(errs, Severity.ERROR, Constants.VE_ANNOTATION_INCOMPATIBLE,
                        type.ensureTypeConstant(ctx).getValueString(),
                        annoAsm.getAnnotationClass().getValueString(),
                        typeAnno.ensureTypeInfo(errs).getInto().getValueString());
                fValid = false;
                }
            }

        return finishValidation(typeRequired, typeReference, fValid ? TypeFit.Fit : TypeFit.NoFit,
                typeReferent, errs);
        }


    // ----- debugging assistance ------------------------------------------------------------------

    @Override
    public String toString()
        {
        StringBuilder sb = new StringBuilder();

        sb.append(annotation)
          .append(' ')
          .append(type);

        return sb.toString();
        }

    @Override
    public String getDumpDesc()
        {
        return toString();
        }


    // ----- fields --------------------------------------------------------------------------------

    protected Annotation     annotation;
    protected TypeExpression type;

    private boolean m_fDisassociateAnnotation;

    private static final Field[] CHILD_FIELDS = fieldsForNames(AnnotatedTypeExpression.class,
            "annotation", "type");
    }
