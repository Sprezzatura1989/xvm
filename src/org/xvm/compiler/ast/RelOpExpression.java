package org.xvm.compiler.ast;


import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool;
import org.xvm.asm.ErrorListener;
import org.xvm.asm.MethodStructure.Code;
import org.xvm.asm.Op.Argument;
import org.xvm.asm.Register;

import org.xvm.asm.constants.ConditionalConstant;
import org.xvm.asm.constants.IntervalConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.asm.op.GP_Add;
import org.xvm.asm.op.Var;

import org.xvm.compiler.Compiler;
import org.xvm.compiler.Token;
import org.xvm.compiler.Token.Id;

import org.xvm.compiler.ast.Statement.Context;

import org.xvm.util.Severity;


/**
 * Relational operator expression (with @Op support) for something that follows the pattern
 * "expression operator expression".
 *
 * <ul>
 * <li><tt>COND_OR:    "||"</tt> - </li>
 * <li><tt>COND_AND:   "&&"</tt> - </li>
 * <li><tt>BIT_OR:     "|"</tt> - </li>
 * <li><tt>BIT_XOR:    "^"</tt> - </li>
 * <li><tt>BIT_AND:    "&"</tt> - </li>
 * <li><tt>DOTDOT:     ".."</tt> - </li>
 * <li><tt>SHL:        "<<"</tt> - </li>
 * <li><tt>SHR:        ">><tt>"</tt> - </li>
 * <li><tt>USHR:       ">>><tt>"</tt> - </li>
 * <li><tt>ADD:        "+"</tt> - </li>
 * <li><tt>SUB:        "-"</tt> - </li>
 * <li><tt>MUL:        "*"</tt> - </li>
 * <li><tt>DIV:        "/"</tt> - </li>
 * <li><tt>MOD:        "%"</tt> - </li>
 * <li><tt>DIVMOD:     "/%"</tt> - </li>
 * </ul>
 *
 * TODO remove cut&paste:
    switch (operator.getId())
        {
        case COND_OR:
        case COND_AND:
        case BIT_OR:
        case BIT_XOR:
        case BIT_AND:
        case DOTDOT:
        case SHL:
        case SHR:
        case USHR:
        case ADD:
        case SUB:
        case MUL:
        case DIV:
        case MOD:
        case DIVMOD:
        }
 */
public class RelOpExpression
        extends BiExpression
    {
    // ----- constructors --------------------------------------------------------------------------

    public RelOpExpression(Expression expr1, Token operator, Expression expr2)
        {
        super(expr1, operator, expr2);

        switch (operator.getId())
            {
            case COND_OR:
            case COND_AND:
            case BIT_OR:
            case BIT_XOR:
            case BIT_AND:
            case DOTDOT:
            case SHL:
            case SHR:
            case USHR:
            case ADD:
            case SUB:
            case MUL:
            case DIV:
            case MOD:
            case DIVMOD:
                break;

            default:
                throw new IllegalArgumentException("operator: " + operator);
            }
        }


    // ----- accessors -----------------------------------------------------------------------------

    @Override
    public TypeExpression toTypeExpression()
        {
        switch (operator.getId())
            {
            case ADD:
            case BIT_OR:
                return new BiTypeExpression(expr1.toTypeExpression(), operator, expr2.toTypeExpression());

            default:
                return super.toTypeExpression();
            }
        }

    @Override
    public boolean validateCondition(ErrorListener errs)
        {
        switch (operator.getId())
            {
            case BIT_AND:
            case COND_AND:
            case BIT_OR:
            case COND_OR:
                return expr1.validateCondition(errs) && expr2.validateCondition(errs);

            default:
                return super.validateCondition(errs);
            }
        }

    @Override
    public ConditionalConstant toConditionalConstant()
        {
        switch (operator.getId())
            {
            case BIT_AND:
            case COND_AND:
                return expr1.toConditionalConstant().addAnd(expr2.toConditionalConstant());

            case BIT_OR:
            case COND_OR:
                return expr1.toConditionalConstant().addOr(expr2.toConditionalConstant());

            default:
                return super.toConditionalConstant();
            }
        }


    // ----- compilation ---------------------------------------------------------------------------


    @Override
    protected Expression validate(Context ctx, TypeConstant typeRequired, TuplePref pref, ErrorListener errs)
        {
        // so let's talk about "+"
        // 1) any T1 that wants to support "+" has to have either:
        //    a) @Op T3 add(T2)
        //    b) @Op("+") T3 foo(T2)
        //    ... where T2 and/or T3 may or may not be the same as T1
        // 2) within this expression, T1 is represented by expr1, and T2 by expr2, and T3 is
        //    represented by this expression itself (or is specified by typeRequired, passed in)
        // 3) so the first thing to do is to validate expr1 (the "this" of the op) to determine its
        //    type
        // 4) having determined its type, the type is asked to enumerate the various potential
        //    matching ops -- i.e. any @Op-annotated method named "add", and any @Op-annotated
        //    method whose annotation specifies the constant "+", and has one parameter and one
        //    return value
        // 5) having determined the possible set of ops (methods), we need to reduce it by
        //    evaluating the typeRequired, if a typeRequired is specified:
        //    a) if the return value type of the op "isA" typeRequired, then that op is a possible
        //    b) if the return value type of the op "is assignable to" (i.e. there exists a to<>()
        //       conversion) to the typeRequired, then that op is a possible
        //    c) all other ops are eliminated
        // 6) if there is only one op left at this point, then validate expr2 using the type
        //    specified as the parameter for the op method
        // 7) otherwise, if there's more than one possibility, then validate expr2 passing null for
        //    the required-type to determine the implicit type
        //    a) for each remaining op, eliminate those that the implicit type of expr2 fails with
        //       both "isA" and "is assignable to"
        //    b) if more than one remains, then we have to find the "closest" using the method
        //       matching rules:
        //       i) exact match wins
        //       ii) "isA" beats "is assignable to"; if there are any "isA" left, then discard all
        //           "is assignable to" options
        //       iii) for any two remaining op candidates, if the parameter type PT1 of one op "isA"
        //            the other PT2, but the reverse is not true, then rank PT1 higher; if they are
        //            both "isA" the other, or neither isA the other, then rank them the same; using
        //            this approach, form a total ranking of the remaining ops
        //       iv) if only one op remains, or if one "isA" rises above all others in the total
        //           ranking, then that is the op to use; otherwise it is an error (ambiguous)
        // TODO

        ConstantPool pool   = pool();
        boolean      fValid = true;

//        expr1.validate(ctx, null, errs);
//        fValid &= expr2.validate(ctx, null, errs);      // TODO need a type here
//
//        // validation of a constant expression is simpler, so do it first
//        TypeConstant type1 = expr1.getType();
//        TypeConstant type2 = expr2.getType();
//        if (isConstant())
//            {
//            // first determine the type of the result, and pick a suitable default value just in
//            // case everything blows up
//            Constant const1 = expr1.toConstant();
//            Constant const2 = expr2.toConstant();
//            switch (operator.getId())
//                {
//                case ADD:
//                case SUB:
//                case MUL:
//                case DIV:
//                case MOD:
//                case BIT_AND:
//                case BIT_OR:
//                case BIT_XOR:
//                    {
//                    TypeConstant typeResult = const1.resultType(operator.getId(), const2);
//                    m_constType = typeResult;
//                    // pick a default value just in case of an exception
//                    m_constVal  = typeResult.isCongruentWith(type1) ? const1
//                                : typeResult.isCongruentWith(type2) ? const2
//                                : const1.defaultValue(typeResult);
//                    }
//                    break;
//
//                case SHL:
//                case SHR:
//                case USHR:
//                    // always use the type on the left hand side, since the numeric shifts all
//                    // take Int64 as the shift amount
//                    m_constType = type1;
//                    m_constVal  = const1;
//                    break;
//
//                case DOTDOT:
//                    m_constType = IntervalConstant.getIntervalTypeFor(const1);
//                    m_constVal  = new IntervalConstant(pool, const1, const1);
//                    break;
//
//                default:
//                    operator.log(errs, getSource(), Severity.ERROR, Compiler.INVALID_OPERATION);
//                    return false;
//                }
//
//            // delegate the operation to the constants
//            try
//                {
//                m_constVal  = const1.apply(operator.getId(), const2);
//                return fValid;
//                }
//            catch (ArithmeticException e)
//                {
//                log(errs, Severity.ERROR, Compiler.VALUE_OUT_OF_RANGE, m_constType,
//                        getSource().toString(getStartPosition(), getEndPosition()));
//                return false;
//                }
//            }

//        // determine the type of this expression; this is even done if the sub-expressions did not
//        // validate, so that compilation doesn't have to grind to a halt for just one error
//        switch (operator.getId())
//            {
//            case COND_OR:
//            case COND_AND:
//                m_constType = pool.typeBoolean();
//                if (fValid)
//                    {
//                    // the left side and right side types must be comparable
//                    // TODO
//                    }
//                break;
//
//            case DOTDOT:
//                m_constType = IntervalConstant.getIntervalTypeFor(expr1.getType());
//                if (fValid)
//                    {
//                    // the left side and right side types must be "the same", and that type must
//                    // be orderable
//                    // TODO
//                    }
//                break;
//
//            case DIVMOD:
//                m_constType = pool.ensureParameterizedTypeConstant(pool.typeTuple(),
//                        expr1.getType(), expr1.getType());
//                if (fValid)
//                    {
//                    // find the operator on the type and determine the result of the operator
//                    // TODO this is an overridable Op
//                    }
//                break;
//
//            case BIT_OR:
//            case BIT_XOR:
//            case BIT_AND:
//            case SHL:
//            case SHR:
//            case USHR:
//            case ADD:
//            case SUB:
//            case MUL:
//            case DIV:
//            case MOD:
//                if (fValid)
//                    {
//                    // find the operator on the type and determine the result of the operator
//                    // TODO these are all overridable Op
//                    }
//                m_constType = expr1.getType();
//                break;
//
//            default:
//                operator.log(errs, getSource(), Severity.ERROR, Compiler.INVALID_OPERATION);
//                m_constType = expr1.getType();
//                fValid = false;
//                break;
//            }

        return fValid
                ? this
                : null;
        }

    @Override
    public boolean isAborting()
        {
        switch (operator.getId())
            {
            case COND_OR:
            case COND_AND:
                // these can complete if the first expression can complete, because the result can
                // be calculated from the first expression, depending on what its answer is; thus
                // the expression aborts if the first of the two expressions aborts
                return expr1.isAborting();

            default:
                // these can only complete if both sub-expressions can complete
                return expr1.isAborting() || expr2.isAborting();
            }
        }

    @Override
    public Argument generateArgument(Code code, boolean fPack, ErrorListener errs)
        {
        if (!isConstant())
            {
            switch (operator.getId())
                {
                case DOTDOT:
                    // TODO
                    throw new UnsupportedOperationException();

                case ADD:
                case SUB:
                case MUL:
                case DIV:
                case MOD:
                case COND_OR:
                case COND_AND:
                case BIT_OR:
                case BIT_XOR:
                case BIT_AND:
                case SHL:
                case SHR:
                case USHR:
                    code.add(new Var(getType()));
                    Register regResult = code.lastRegister();
                    generateAssignment(code, new Assignable(regResult), errs);
                    return regResult;

                case DIVMOD:
                    // TODO
                    throw new UnsupportedOperationException();
                }
            }

        return super.generateArgument(code, fPack, errs);
        }

    @Override
    public Argument[] generateArguments(Code code, boolean fPack, ErrorListener errs)
        {
        if (getValueCount() == 2)
            {
            assert operator.getId() == Id.DIVMOD;
            // TODO
            throw new UnsupportedOperationException();
            }

        return super.generateArguments(code, fPack, errs);
        }

    @Override
    public void generateAssignment(Code code, Assignable LVal, ErrorListener errs)
        {
        if (LVal.isLocalArgument())
            {
            // evaluate the sub-expressions
            Argument arg1 = expr1.generateArgument(code, false, errs);
            Argument arg2 = expr2.generateArgument(code, false, errs);

            // generate the op that combines the two sub-expressions
            switch (operator.getId())
                {
                case COND_OR:
                    // TODO
                    throw new UnsupportedOperationException();

                case COND_AND:
                    // TODO
                    throw new UnsupportedOperationException();

                case BIT_OR:
                    // TODO
                    throw new UnsupportedOperationException();

                case BIT_XOR:
                    // TODO
                    throw new UnsupportedOperationException();

                case BIT_AND:
                    // TODO
                    throw new UnsupportedOperationException();

                case DOTDOT:
                    // TODO
                    throw new UnsupportedOperationException();

                case SHL:
                    // TODO
                    throw new UnsupportedOperationException();

                case SHR:
                    // TODO
                    throw new UnsupportedOperationException();

                case USHR:
                    // TODO
                    throw new UnsupportedOperationException();

                case ADD:
                    code.add(new GP_Add(arg1, arg2, LVal.getLocalArgument()));
                    break;

                case SUB:
                    // TODO
                    throw new UnsupportedOperationException();

                case MUL:
                    // TODO
                    throw new UnsupportedOperationException();

                case DIVMOD:
                    if (LVal.getType().isTuple())
                        {
                        // TODO
                        throw new UnsupportedOperationException();
                        }
                    // fall through
                case DIV:
                    // TODO
                    throw new UnsupportedOperationException();

                case MOD:
                    // TODO
                    throw new UnsupportedOperationException();
                }

            return;
            }

        super.generateAssignment(code, LVal, errs);
        }

    @Override
    public void generateAssignments(Code code, Assignable[] aLVal, ErrorListener errs)
        {
        if (getValueCount() == 2)
            {
            assert operator.getId() == Id.DIVMOD;
            // TODO
            throw new UnsupportedOperationException();
            }

        super.generateAssignments(code, aLVal, errs);
        }


    // ----- fields --------------------------------------------------------------------------------

    }
