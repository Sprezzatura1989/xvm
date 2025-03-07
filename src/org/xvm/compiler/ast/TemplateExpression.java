package org.xvm.compiler.ast;


import java.lang.reflect.Field;

import java.util.List;

import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool;
import org.xvm.asm.ErrorListener;
import org.xvm.asm.MethodStructure.Code;
import org.xvm.asm.Register;

import org.xvm.asm.constants.MethodConstant;
import org.xvm.asm.constants.StringConstant;
import org.xvm.asm.constants.TypeConstant;
import org.xvm.asm.constants.TypeInfo;

import org.xvm.asm.op.Enter;
import org.xvm.asm.op.Exit;
import org.xvm.asm.op.Invoke_01;
import org.xvm.asm.op.Invoke_10;
import org.xvm.asm.op.New_1;

import org.xvm.compiler.Token;
import org.xvm.compiler.Token.Id;

import org.xvm.util.Handy;

import static org.xvm.asm.Assignment.AssignedOnce;


/**
 * A template expression is a string literal expression containing expressions that will be
 * evaluated and concatenated with the literal portions to produce a resulting string.
 *
 * TODO optimize handling for: $.append($"...") for "append()", "add()"/"+", etc.
 */
public class TemplateExpression
        extends Expression
    {
    // ----- constructors --------------------------------------------------------------------------

    public TemplateExpression(List<Expression> exprs, long lStartPos, long lEndPos)
        {
        this.exprs     = exprs;
        this.lStartPos = lStartPos;
        this.lEndPos   = lEndPos;
        }


    // ----- accessors -----------------------------------------------------------------------------

    public List<Expression> getExpressions()
        {
        return exprs;
        }

    @Override
    public long getStartPosition()
        {
        return lStartPos;
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


    // ----- compilation ---------------------------------------------------------------------------

    @Override
    public TypeConstant getImplicitType(Context ctx)
        {
        return pool().typeString();
        }

    @Override
    protected Expression validate(Context ctx, TypeConstant typeRequired, ErrorListener errs)
        {
        boolean fValid = true;
        boolean fConst = true;

        ctx = ctx.enter();

        final TypeConstant   T_OBJECT = pool().typeObject();
        final TypeConstant   T_STRING = pool().typeString();
        final TypeConstant[] A_OBJECT = new TypeConstant[] {T_OBJECT};
        final TypeConstant[] A_STRING = new TypeConstant[] {T_STRING};

        // declare a "$" variable (replacing one if it already exists)
        m_reg$ = new Register(pool().typeStringBuffer());
        Token tok$ = new Token(getStartPosition(), getStartPosition(), Id.IDENTIFIER, "$");
        ctx.registerVar(tok$, m_reg$, errs);
        ctx.setVarAssignment("$", AssignedOnce);

        // validate the expressions that make up the template
        int cExprs = exprs.size();
        for (int i = 0; i < cExprs; ++i)
            {
            Expression     exprOld = exprs.get(i);
            TypeConstant[] atypeExpr;
            if (exprOld.testFit(ctx, T_STRING, null).isFit())
                {
                atypeExpr = A_STRING;
                }
            else if (exprOld.testFit(ctx, T_OBJECT, null).isFit())
                {
                atypeExpr = A_OBJECT;
                }
            else
                {
                // void expression (e.g. a lambda-style expr explicitly appending to "$"); note that
                // this is also a catch-all for expressions that will fail to validate (and thus
                // have failed to respond to testFit())
                atypeExpr = TypeConstant.NO_TYPES;
                }

            Expression exprNew = exprOld.validateMulti(ctx, atypeExpr, errs);
            if (exprNew == null)
                {
                fValid = false;
                }
            else
                {
                if (exprNew != exprOld)
                    {
                    exprs.set(i, exprNew);
                    }

                fConst &= exprNew.isConstant();
                }
            }

        // build the constant value for this template expression, if possible
        Constant constVal = null;
        if (fValid && fConst)
            {
            StringBuilder sb = new StringBuilder();
            for (Expression expr : exprs)
                {
                TypeConstant[] atype = expr.getTypes();
                if (atype.length > 0)
                    {
                    Constant constExpr = expr.toConstant().convertTo(T_STRING);
                    if (constExpr == null)
                        {
                        fConst = false;
                        break;
                        }
                    sb.append(((StringConstant) constExpr).getValue());
                    }
                }
            constVal = fConst ? pool().ensureStringConstant(sb.toString()) : null;
            }

        ctx = ctx.exit();

        return finishValidation(typeRequired, T_STRING, fValid ? TypeFit.Fit : TypeFit.NoFit, constVal, errs);
        }

    @Override
    public void generateAssignment(Context ctx, Code code, Assignable LVal, ErrorListener errs)
        {
        if (isConstant())
            {
            LVal.assign(toConstant(), code, errs);
            return;
            }

        // use the super-class implementation of generateAssignment() to create a temp variable
        // to avoid having to provide handling for complex LVals in this method
        if (!LVal.isLocalArgument())
            {
            super.generateAssignment(ctx, code, LVal, errs);
            return;
            }

        code.add(new Enter());

        // each expression is either:
        // - a literal (StringConstant), which is appended as-is;
        // - an expression that yields void (it is assumed that any append occurs within the
        //   expression);
        // - an expression that yields a Stringable, which is then appended to the buffer;
        // - an expression that yields an Object, which is then converted to a String, and
        //   appended to the buffer.
        // unfortunately, the evaluation (including appending) order must be strictly
        // left-to-right, because side-effects of the expressions are unknown
        //
        // pseudo-code:
        //   Int minlen = sum of StringConstant lengths
        //   StringBuffer $ = new StringBuffer(minlen);
        //   $.append("...");
        //   expr1.appendTo($);             // for a Stringable
        //   $.append("...");
        //   $.append(expr2);               // for an object
        //   $.append("...");
        //   {...}                          // for a void expression
        //   $.append("...");
        //   yield $.toString();
        int          cchMin   = 0;
        ConstantPool pool     = pool();
        for (Expression expr : exprs)
            {
            if (isStringConst(expr))
                {
                cchMin += getStringConst(expr).length();
                }
            }

        // $ = new StringBuffer(cchMin)
        TypeConstant   typeBuf  = pool.typeStringBuffer();
        TypeInfo       infoBuf  = typeBuf.ensureTypeInfo(errs);
        TypeConstant   typeInt  = pool.typeInt();
        MethodConstant idNewBuf = infoBuf.findConstructor(new TypeConstant[] {typeInt}, null);
        assert idNewBuf != null;
        code.add(new New_1(idNewBuf, pool.ensureIntConstant(cchMin), m_reg$));

        TypeConstant   typeStr    = pool.typeStringable();
        TypeInfo       infoStr    = typeStr.ensureTypeInfo(errs);
        Assignable     lvalStr    = createTempVar(code, typeStr, true, errs);
        TypeConstant   typeObj    = pool.typeObject();
        Assignable     lvalObj    = createTempVar(code, typeObj, true, errs);
        MethodConstant idAppendTo = infoStr.findCallable("appendTo", true, false, null, null, null);
        MethodConstant idAppend   = infoBuf.findCallable("append"  , true, false, null, null, null);
        for (Expression expr : exprs)
            {
            if (isStringConst(expr))
                {
                // $.append("...");
                code.add(new Invoke_10(expr.toConstant(), idAppendTo, m_reg$));
                }
            else if (expr.isVoid())
                {
                // {...}
                expr.generateVoid(ctx, code, errs);
                }
            else if (isStringable(expr))
                {
                // expr1.appendTo($);
                expr.generateAssignment(ctx, code, lvalStr, errs);
                code.add(new Invoke_10(lvalStr.getLocalArgument(), idAppendTo, m_reg$));
                }
            else
                {
                // $.append(expr2);
                expr.generateAssignment(ctx, code, lvalObj, errs);
                // even though append returns a buffer, we already have the buffer, so we use the
                // invoke_10 instead of the (more correct) invoke_11
                code.add(new Invoke_10(m_reg$, idAppend, lvalObj.getLocalArgument()));
                }
            }

        // yield $.toString();
        MethodConstant idToString = infoBuf.findCallable("toString", true, false,
                new TypeConstant[] {pool.typeString()}, null, null);
        code.add(new Invoke_01(m_reg$, idToString, LVal.getLocalArgument()));

        code.add(new Exit());
        }


    // ----- debugging assistance ------------------------------------------------------------------

    @Override
    public String toString()
        {
        StringBuilder sb = new StringBuilder();

        sb.append("$\"");

        for (Expression expr : exprs)
            {
            if (isStringConst(expr))
                {
                for (char ch : ((StringConstant) expr.toConstant()).getValue().toCharArray())
                    {
                    Handy.appendChar(sb, ch);
                    }
                }
            else
                {
                sb.append('{')
                  .append(expr)
                  .append('}');
                }
            }

        sb.append('\"');

        return sb.toString();
        }

    @Override
    public String getDumpDesc()
        {
        return toString();
        }


    // ----- helpers -------------------------------------------------------------------------------

    boolean isStringConst(Expression expr)
        {
        return expr.isConstant() && expr.getTypes().length > 0 && expr.toConstant() instanceof StringConstant;
        }

    String getStringConst(Expression expr)
        {
        return ((StringConstant) expr.toConstant()).getValue();
        }

    boolean isStringable(Expression expr)
        {
        return expr.getTypes().length > 0 && expr.getType().isA(pool().typeStringable());
        }


    // ----- fields --------------------------------------------------------------------------------

    protected TypeExpression   type;
    protected List<Expression> exprs;
    protected long             lStartPos;
    protected long             lEndPos;

    private Register m_reg$;

    private static final Field[] CHILD_FIELDS = fieldsForNames(TemplateExpression.class, "type", "exprs");
    }
