
#
# misc
#

QualifiedName
    Name
    QualifiedName "." Name

Modifiers
    Modifier
    Modifiers Modifier

Modifier
    "static"
    AccessModifier
    Annotation

AccessModifier
    "public"
    "protected"
    "private"

Annotations
    Annotation
    Annotations Annotation

Annotation
    "@" NoWhitespace NamedTypeExpression NoWhitespace ArgumentList-opt

ParameterList
    "(" Parameters ")"

Parameters
    Parameter
    Parameters "," Parameter

Parameter
    TypeExpression Name DefaultValue-opt

DefaultValue
    "=" Expression

ArgumentList
    "(" Arguments-opt ")"

Arguments
    Argument
    Arguments "," Argument

Argument
    NamedArgument-opt ArgumentExpression

# note: the "_" argument allows functions to specify arguments that they are NOT binding
ArgumentExpression
    "_"
    "<" TypeExpression ">" "_"
    Expression

NamedArgument
    Name "="

TypeParameterList
    "<" TypeParameters ">"

TypeParameters
    TypeParameter
    TypeParameters "," TypeParameter

TypeParameter
    Name TypeParameterConstraint-opt

TypeParameterConstraint
    "extends" TypeExpression

TypeParameterTypeList
    "<" TypeExpressionList ">"

TypeExpressionList
    TypeExpression
    TypeExpressionList "," TypeExpression

#
# compilation unit
#

CompilationUnit
	ImportStatements-opt TypeCompositionStatement

ImportStatements
	ImportStatement
	ImportStatements ImportStatement

#
# type compositions
#

TypeCompositionStatement
    Modifiers-opt Category QualifiedName TypeParameterList-opt ParameterList-opt Compositions-opt TypeCompositionBody

Category
    "module"
    "package"
    "class"
    "interface"
    "service"
    "const"
    "enum"
    "mixin"

Compositions
    ConditionalComposition
    Compositions ConditionalComposition

# while parsing is of a generic Expression, there are only a few expression forms that are permitted:
# 1. StringLiteral "." "defined"
# 2. QualifiedName "." "present"
# 3. QualifiedName "." "versionMatches" "(" VersionLiteral ")"
# 4. Any of 1-3 and 5 negated using "!"
# 5. Any two of 1-5 combined using "&", "&&", "|", or "||"
ConditionalComposition
    IfComposition
    Composition

IfComposition
    "if" "(" Expression ")" "{" Compositions "}" ElseComposition-opt

ElseComposition
    "else" IfComposition
    "else" "{" Compositions "}"

Composition
    "extends" TypeExpression ArgumentList-opt
    "implements" TypeExpression
    "delegates" TypeExpression "(" Expression ")"
    "incorporates" IncorporatesFinish
    "into" TypeExpression
    ImportClause QualifiedName VersionRequirement-opt
    "default" "(" Expression ")"

IncorporatesFinish
    "conditional" QualifiedName TypeParameterList ArgumentList-opt
    TypeExpression ArgumentList-opt

ImportClause
    "import"
    "import:embedded"
    "import:required"
    "import:desired"
    "import:optional"

VersionRequirement
    Version VersionOverrides-opt

VersionOverrides
    VersionOverride
    VersionOverrides "," VersionOverride

VersionOverride
    VersionOverrideVerb Versions

VersionOverrideVerb
    "allow"
    "avoid"
    "prefer"

Versions
    Version
    Versions, Version

Version
    VersionLiteral

# note: EnumBody is only valid (and is not actually optional) for the "enum" category, but that
# check can be deferred to a syntactic or semantic analysis phase
# note: an empty body is rare, but does occur e.g. "package x import ..", and simple classes
# with constructors specified in the type composition e.g. "const Point(Int x, Int y);"
TypeCompositionBody
    "{" EnumBody "}"
    "{" TypeCompositionComponents "}"
    ";"

EnumBody
    Enums EnumBodyFinish-opt

Enums
    Enum
    Enums "," Enum

EnumBodyFinish
    ";" TypeCompositionComponents

Enum
    Annotations-opt Name TypeParameterTypeList-opt ArgumentList-opt TypeCompositionBody-opt

TypeCompositionComponents
    ConditionalTypeCompositionComponent
    TypeCompositionComponents ConditionalTypeCompositionComponent

ConditionalTypeCompositionComponent
    IfTypeCompositionComponent
    TypeCompositionComponent

# see special notes on ConditionalComposition related to the Expression
IfTypeCompositionComponent
    "if" "(" Expression ")" "{" TypeCompositionComponents "}" ElseTypeCompositionComponent-opt

ElseTypeCompositionComponent
    "else" IfTypeCompositionComponent
    "else" "{" TypeCompositionComponents "}"

TypeCompositionComponent
    AccessModifier-opt TypeDefStatement
    ImportStatement
    TypeCompositionStatement
    PropertyDeclarationStatement
    MethodDeclarationStatement

#
# properties
#

PropertyDeclarationStatement
    PropertyModifiers-opt TypeExpression Name PropertyDeclarationFinish-opt

PropertyModifiers
    PropertyModifier
    PropertyModifiers PropertyModifiers

PropertyModifier
    "static"
    PropertyAccessModifier
    Annotation

PropertyAccessModifier
    AccessModifier
    AccessModifier "/" AccessModifier

PropertyDeclarationFinish
    "=" Expression ";"
    "." Name Parameters MethodBody
    TypeCompositionBody

#
# methods
#

MethodDeclarationStatement
    MethodModifiers-opt TypeParameterList-opt MethodIdentity ParameterList MethodDeclarationFinish

MethodModifiers
    MethodModifier
    MethodModifiers MethodModifier

MethodModifier
    Modifier
    Annotation

MethodIdentity
    "construct"
    "conditional"-opt ReturnList Name RedundantReturnSpecifier-opt

ReturnList
    "void"
    SingleReturnList
    "(" MultiReturnList ")"

SingleReturnList
    TypeExpression

MultiReturnList
    MultiReturn
    MultiReturnList "," MultiReturn

MultiReturn
    TypeExpression Name-opt

RedundantReturnSpecifier
    "<" TypeExpressionList ">"

MethodDeclarationFinish
    ;
    StatementBlock

#
# statements
#

# note: not explicitly spelling out the grammar necessary to avoid the dangling "else" problem, but
#       the approach is identical to C/Java in that the parser greedily looks for an else, causing
#       the else to be associated with the inner-most "if" that is in the parse stack
# note: no "empty statement"
Statement
    TypeCompositionStatement
    PropertyDeclarationStatement        # note: always "static" or "private"
    MethodDeclarationStatement
	VariableDeclaration ";"
	Assignment ";"
    LabeledStatement
    AssertStatement
    BreakStatement
    ContinueStatement
    "do" StatementBlock "while" "(" ConditionList ")" ";"
    ForStatement
    IfStatement
	ImportStatement
	ReturnStatement
    SwitchStatement
    TryStatement
	TypeDefStatement
    "using" ResourceDeclaration StatementBlock
    "while" "(" ConditionList ")" StatementBlock
    WithStatement
    StatementBlock
	Expression ";"      // for parsing purposes (compilation will only allow specific expression forms)

StatementBlock
    "{" Statements "}"

Statements
    Statement
    Statements Statement

VariableDeclaration
    VariableTypeExpression Name VariableInitializerFinish-opt
    "(" OptionalDeclarationList "," OptionalDeclaration ")" "=" Expression

VariableInitializerFinish
    "=" Expression

OptionalDeclarationList
    OptionalDeclaration
    OptionalDeclarationList "," OptionalDeclaration

OptionalDeclaration
    Assignable
    VariableTypeExpression Name

VariableTypeExpression
    "val"
    "var"
    TypeExpression

Assignment
    Assignee AssignmentOperator Expression

Assignee
    Assignable
    "(" AssignableList "," Assignable ")"

AssignableList
    Assignable
    AssignableList "," Assignable

# Assignable turns out to be just an Expression that meets certain requirements, i.e. one that ends
# with a Name or an ArrayIndexes
Assignable
    Name
    TernaryExpression "." Name
    TernaryExpression ArrayIndexes

AssignmentOperator
    "="                 // straight assignment
    "*="                // multiply-assign
    "/="                // divide-assign
    "%="                // modulo-assign
    "+="                // add-assign
    "-="                // subtract-assign
    "<<="               // shift-left-assign
    ">>="               // shift-right-assign
    ">>>="              // unsigned-shift-right-assign
    "&="                // and-assign
    "&&="               // and-assign (short-circuiting)
    "|="                // or-assign
    "||="               // or-assign (short-circuiting)
    "^="                // xor-assign
    "?:="               // elvis-assign (assigns only if the LVal is null)
    ":="                // conditional assign (RVal must be @Conditional; assigns starting with 2nd tuple field iff expression is true)
    "?="                // assigns only if the RVal is not null (also used in conditional statements e.g. "if" to produce conditional False for Null)

LabeledStatement
    Name ":" Statement

AssertStatement
    AssertInstruction ConditionList-opt ";"

AssertInstruction                               # (when active, what gets thrown)
    "assert"                                    # runtime, IllegalState
    "assert:arg"                                # runtime, IllegalArgument
    "assert:bounds"                             # runtime, OutOfBounds
    "assert:TODO"                               # runtime, UnsupportedOperation
    "assert:once"                               # runtime, Assertion (only tested "the first time")
    "assert:rnd(" Expression ")"                # runtime (sampling), IllegalState
    "assert:test"                               # test mode (e.g. CI/QC), Assertion
    "assert:debug"                              # debug mode, breakpoint-only (i.e. no throw)

ForStatement
    "for" "(" ForCondition ")" StatementBlock

ForCondition
    VariableInitializationList-opt ";" ConditionList-opt ";" VariableModificationList-opt
    OptionalDeclaration ":" Expression
    (OptionalDeclarationList, OptionalDeclaration) ":" Expression

VariableInitializationList
    VariableInitializer
    VariableInitializationList "," VariableInitializer

VariableInitializer
    OptionalDeclaration "=" Expression
    "(" OptionalDeclarationList "," OptionalDeclaration ")" "=" Expression

VariableModificationList
    VariableModification
    VariableModificationList "," VariableModification

VariableModification
    Assignment
    Expression    # note: expression must have side-effects (i.e. not a constant)

IfStatement
    "if" "(" ConditionList ")" StatementBlock ElseStatement-opt

ConditionList
    Condition
    ConditionList, Condition

Condition
    Expression
    OptionalDeclaration ConditionalAssignmentOp Expression
    ( OptionalDeclarationList, OptionalDeclaration ) ConditionalAssignmentOp Expression

ConditionalAssignmentOp
    :=
    ?=

ElseStatement
    "else" IfStatement
    "else" StatementBlock

ImportStatement
    "import" QualifiedName ImportFinish

ImportFinish
    ";"
    "as" Name ";"
    NoWhitespace ".*" ";"

ReturnStatement
    "return" ReturnValue-opt ";"

ReturnValue
    TupleLiteral
    ExpressionList

SwitchStatement
    "switch" "(" SwitchCondition-opt ")" "{" SwitchBlocks "}"

SwitchCondition
    SwitchConditionExpression
    SwitchCondition "," SwitchConditionExpression

SwitchConditionExpression
    VariableInitializer
    Expression

SwitchBlocks
    SwitchBlock
    SwitchBlocks SwitchBlock

# the SwitchBlockFinish is required unless the SwitchBlock does not complete (e.g. ends with a "throw")
SwitchBlock
    SwitchLabels Statements SwitchBlockFinish-opt

SwitchLabels
    SwitchLabel
    SwitchLabels SwitchLabel

# 1) for a SwitchStatement with a SwitchCondition, each "case" expression must be a
#    "constant expression", i.e. compiler has to be able to determine the value (or a constant that
#    points to a value that is constant at run-time, e.g. a property constant for a static property)
# 2) for a SwitchStatement without a SwitchCondition, each "case" expression must be of type Boolean
#    and is not required to be a constant
# 3) for a SwitchStatement with a SwitchCondition, a case may specify a list of values, which is
#    semantically identical to having that same number of "case" labels each with one of those values.
# 4) for a SwitchStatement with multiple SwitchConditionExpressions in the SwitchCondition or with
#    a single SwitchConditionExpression of a tuple type, each "case" value must be either:
#    (a) a parenthesized list of expressions (a compatible tuple constant), or
#    (b) a constant expression of a compatible tuple type
# 5) each "case" expression may be any of:
#    (a) the type of the corresponding expression (or tuple field value) in the SwitchCondition;
#    (b) an Interval of that type; or
#    (c) the wild-card "_" (compiled as the "blackhole" constant)
#    a CaseExpressionList of all wild-cards is semantically equivalent to the use of a "default"
#    label, and would predictably conflict with the same if both were specified.
SwitchLabel
    "case" CaseOptionList ":"
    "default" ":"

SwitchBlockFinish:
    BreakStatement
    ContinueStatement

BreakStatement:
    "break" Name-opt ";"

ContinueStatement:
    "continue" Name-opt ";"

CaseOptionList:
    CaseOption
    CaseOptionList "," CaseOption

CaseOption:
    "(" CaseExpressionList "," CaseExpression ")"
    SafeCaseExpression

CaseExpressionList:
    CaseExpression
    CaseExpressionList "," CaseExpression

CaseExpression:
    "_"
    Expression

# parse for "case TernaryExpression:" because Expression parsing looks for a possible trailing ':'
SafeCaseExpression:
    "_"
    TernaryExpression

TryStatement
    "try" ResourceDeclaration-opt StatementBlock TryFinish

ResourceDeclaration
    "(" VariableInitializationList ")"

TryFinish
    Catches
    Catches-opt "finally" StatementBlock

Catches
    Catch
    Catches Catch

Catch
    "catch" "(" TypeExpression Name ")" StatementBlock

TypeDefStatement
    "typedef" TypeExpression "as"-opt Name ";"

#
# expressions
#

#   Operator        Description             Level   Associativity
#   --------------  ----------------------  -----   -------------
#   ++              post-increment            1     left to right
#   --              post-decrement
#   ()              invoke a method
#   []              access array element
#   ?               conditional
#   .               access object member
#   .new            postfix object creation
#   .as             postfix type assertion
#   .is             postfix type comparison
#
#   ++              pre-increment             2     right to left
#   --              pre-decrement
#   +               unary plus
#   -               unary minus
#   !               logical NOT
#   ~               bitwise NOT
#   &               reference-of
#
#   ?:              conditional elvis         3     right to left
#
#   *               multiplicative            4     left to right
#   /
#   %
#   /%
#
#   +               additive                  5     left to right
#   -
#
#   << >>           bitwise                   6     left to right
#   >>>
#   &
#   ^
#   |
#
#   ..              range/interval            7     left to right
#
#   <  <=           relational                8     left to right
#   >  >=
#   <=>             order ("star-trek")
#
#   ==              equality                  9     left to right
#   !=
#
#   &&              conditional AND          10     left to right
#
#   ^^              conditional XOR          11     left to right
#   ||              conditional OR
#
#   ? :             conditional ternary      12     right to left
#
#   :               conditional ELSE         13     right to left

Expression
    TernaryExpression
    TernaryExpression ":" Expression

# whitespace is documented here to differentiate from the conditional expression of the form "e?"
TernaryExpression
    OrExpression
    OrExpression Whitespace "?" OrExpression ":" TernaryExpression

OrExpression
    XorExpression
    OrExpression || XorExpression

XorExpression
    AndExpression
    XorExpression ^^ AndExpression

AndExpression
    EqualityExpression
    AndExpression && EqualityExpression

EqualityExpression
    RelationalExpression
    EqualityExpression "==" RelationalExpression
    EqualityExpression "!=" RelationalExpression

RelationalExpression
    RangeExpression
    RelationalExpression "<"   RangeExpression
    RelationalExpression ">"   RangeExpression
    RelationalExpression "<="  RangeExpression
    RelationalExpression ">="  RangeExpression
    RelationalExpression "<=>" RangeExpression

RangeExpression
    BitwiseExpression
    RangeExpression ".." BitwiseExpression

BitwiseExpression
    AdditiveExpression
    BitwiseExpression "<<"  AdditiveExpression
    BitwiseExpression ">>"  AdditiveExpression
    BitwiseExpression ">>>" AdditiveExpression
    BitwiseExpression "&"   AdditiveExpression
    BitwiseExpression "^"   AdditiveExpression
    BitwiseExpression "|"   AdditiveExpression

AdditiveExpression
    MultiplicativeExpression
    AdditiveExpression "+" MultiplicativeExpression
    AdditiveExpression "-" MultiplicativeExpression

MultiplicativeExpression
    ElvisExpression
    MultiplicativeExpression "*"  ElvisExpression
    MultiplicativeExpression "/"  ElvisExpression
    MultiplicativeExpression "%"  ElvisExpression
    MultiplicativeExpression "/%" ElvisExpression

ElvisExpression
    PrefixExpression
    PrefixExpression ?: ElvisExpression

PrefixExpression
    PostfixExpression
    "++" PrefixExpression
    "--" PrefixExpression
    "+" PrefixExpression
    "-" PrefixExpression
    "!" PrefixExpression
    "~" PrefixExpression

# see comment on primary expression to understand why type parameter list needs to be parsed as part
# of name
PostfixExpression
    PrimaryExpression
    PostfixExpression "++"
    PostfixExpression "--"
    PostfixExpression "(" Arguments-opt ")"
    PostfixExpression ArrayDims                                 # TODO REVIEW - is this correct? (does it imply that the expression is a type expression?)
    PostfixExpression "..."                                     # TODO REVIEW - is this correct? (does it imply that the expression is a type expression?)
    PostfixExpression ArrayIndexes
    PostfixExpression NoWhitespace "?"
    PostfixExpression "." "&"-opt Name TypeParameterTypeList-opt
    PostfixExpression ".new" TypeExpression "(" Arguments-opt ")"
    PostfixExpression ".as" "(" TypeExpression ")"
    PostfixExpression ".is" "(" TypeExpression ")"

ArrayDims
    "[" DimIndicators-opt "]"

DimIndicators
    DimIndicator
    DimIndicators "," DimIndicator

DimIndicator
    "?"

ArrayIndexes
    "[" ExpressionList "]"

ExpressionList
    Expression
    ExpressionList "," Expression


# Note: A parenthesized Expression, a TupleLiteral, and a LambdaExpression share a parse path
# Note: The use of QualifiedName instead of a simple Name here (which would be logical and even
#       expected since PostfixExpression takes care of the ".Name.Name" etc. suffix parsing) is
#       used to capture the case where the expression is a type expression containing type
#       parameters, and which the opening '<' of the type parameters would be parsed by the
#       RelationalExpression rule if we miss handling it here. Unfortunately, that means that the
#       TypeParameterList is parsed speculatively if the '<' opening token is encountered after
#       a name, because it could (might/will occasionally) still be a "less than sign" and not a
#       parametized type.
PrimaryExpression
    "(" Expression ")"
    "new" TypeExpression "(" Arguments-opt ")" AnonClassBody-opt
    "throw" TernaryExpression
    "T0D0" TodoFinish-opt
    "assert"
    "&"-opt "construct"-opt QualifiedName TypeParameterTypeList-opt
    StatementExpression
    SwitchExpression
    LambdaExpression
    "_"
    Literal

AnonClassBody
    "{" TypeCompositionComponents "}"

# a statement expression is a lambda with an implicit "()->" preamble and with an implicit "()"
# trailing invocation, i.e. it is a block of statements that executes, and at the end, it must
# return a value (unlike a "naked" lambda, it can not just be an expression)
StatementExpression
    StatementBlock

SwitchExpression
    "switch" "(" SwitchCondition-opt ")" "{" SwitchExpressionBlocks "}"

SwitchExpressionBlocks
    SwitchExpressionBlock
    SwitchExpressionBlocks SwitchExpressionBlock

SwitchExpressionBlock
    SwitchLabels ExpressionList ;

LambdaExpression
    LambdaInputs "->" LambdaBody

LambdaInputs
    LambdaParameterName
    LambdaInferredList
    LambdaParameterList

LambdaInferredList
    "(" LambdaParameterNames ")"

LambdaParameterNames
    LambdaParameterName
    LambdaParameterNames "," LambdaParameterName

LambdaParameterList
    "(" LambdaParameters ")"

LambdaParameters
    LambdaParameter
    LambdaParameters "," LambdaParameter

LambdaParameter
    TypeExpression LambdaParameterName

LambdaParameterName
    _
    Name

LambdaBody
    ElvisExpression
    StatementBlock

TodoFinish
    InputCharacter-not-"(" InputCharacters LineTerminator
    NoWhitespace "(" Expression ")"

Literal
    IntLiteral                                                  # defined in language spec
    FPDecimalLiteral                                            # defined in language spec
    FPBinaryLiteral                                             # defined in language spec
    CharLiteral                                                 # defined in language spec
    StringLiteral
    BinaryLiteral
    TupleLiteral
    ListLiteral
    MapLiteral
    VersionLiteral
    DateLiteral
    TimeLiteral
    DateTimeLiteral
    TimeZoneLiteral
    DurationLiteral
    PathLiteral
    FileLiteral
    DirectoryLiteral
    FileStoreLiteral

StringLiteral
    "$"-opt NoWhitespace '"' CharacterString-opt '"'
    "`|" FreeformLiteral
    "$|" FreeformLiteral
    "$" NoWhitespace File                                       # value is String contents of file

FreeformLiteral
    FreeformChars LineTerminator FreeformLines-opt

FreeformLines
    FreeformLine
    FreeformLines FreeformLine

FreeformLine
    Whitespace-opt "|" FreeformChars LineTerminator

FreeformChars
    FreeformChar
    FreeformChars FreeformChar

FreeformChar
    InputCharacter except LineTerminator

BinaryLiteral
    "#" NoWhitespace Hexits                                     # "Hexits" defined in language spec
    "#|" FreeformLiteral                                        # containing only Hexits and whitespace
    "#" NoWhitespace File                                       # file to include as binary data

TupleLiteral
    "(" ExpressionList "," Expression ")"                       # compile/runtime type is Tuple
    TypeExpression NoWhitespace ":(" ExpressionList-opt ")"     # type must be a Tuple

CollectionLiteral
    "[" ExpressionList-opt "]"                                  # compile/runtime type is Array
    TypeExpression ":[" ExpressionList-opt "]"                  # type must be Collection, Sequence, or List

MapLiteral
    "[" Entries-opt "]"                                         # compile/runtime type is Map
    TypeExpression ":[" Entries-opt "]"                         # type must be Map

Entries
    Entry
    Entries "," Entry

Entry
    Expression "=" Expression

VersionLiteral
    "Version:" NoWhitespace VersionString
    "v:" NoWhitespace VersionString

VersionString
    NonGASuffix
    VersionNumbers VersionFinish-opt

VersionNumbers
    DigitsNoUnderscores
    VersionNumbers "." DigitsNoUnderscores

VersionFinish:
     "." NonGASuffix

NonGASuffix
      NonGAPrefix DigitsNoUnderscores-opt

NonGAPrefix:        # note: not (!!!) case sensitive
    "dev"           # developer build (default compiler stamp)
    "ci"            # continuous integration build (automated build, automated test)
    "qc"            # build selected for internal Quality Control
    "alpha"         # build selected for external alpha test (pre-release)
    "beta"          # build selected for external beta test (pre-release)
    "rc"            # build selected as a release candidate (pre-release; GA pending)

DateLiteral
    "Date:" Digit Digit Digit Digit "-" Digit Digit "-" Digit Digit         # NoWhitespace

TimeLiteral
    "Time:" Digit Digit ":" Digit Digit Seconds-opt                         # NoWhitespace

Seconds
     ":" Digit Digit SecondsFraction-opt                                    # NoWhitespace

SecondsFraction
     "." NoWhitespace Digits

# with NoWhitespace
DateTimeLiteral
    "DateTime:" Digit Digit Digit Digit "-" Digit Digit "-" Digit Digit "T" Digit Digit ":" Digit Digit Seconds-opt TimeZone-opt

TimeZoneLiteral
    "TimeZone:" NoWhitespace TimeZone

TimeZone
    "Z"
    "+" NoWhitespace Digit NoWhitespace Digit NoWhitespace MinutesOffset-opt
    "-" NoWhitespace Digit NoWhitespace Digit NoWhitespace MinutesOffset-opt

MinutesOffset
    ":" NoWhitespace Digit NoWhitespace Digit

# using ISO 8601 "PnYnMnDTnHnMnS" format, with NoWhitespace
DurationLiteral
    "Duration:P" YearsDuration-opt MonthsDuration-opt DaysDuration-opt TimeDuration-opt

TimeDuration
     "T" NoWhitespace HoursDuration-opt NoWhitespace MinutesDuration-opt NoWhitespace SecondsDuration-opt

YearsDuration
    DigitsNoUnderscores NoWhitespace "Y"

MonthsDuration
    DigitsNoUnderscores NoWhitespace "M"

DaysDuration
    DigitsNoUnderscores NoWhitespace "D"

HoursDuration
    DigitsNoUnderscores NoWhitespace "H"

MinutesDuration
    DigitsNoUnderscores NoWhitespace "M"

SecondsDuration
    DigitsNoUnderscores NoWhitespace "S"

PathLiteral
    "Path:" NoWhitespace Dir NoWhitespace PathName-opt

FileLiteral
    "File:"-opt NoWhitespace File

DirectoryLiteral
    "Directory:"-opt NoWhitespace Dir

FileStoreLiteral
    "FileStore:" NoWhitespace Dir NoWhitespace PathName-opt

# Dir and File paths are not intended to support all possible directory and file names -- just the
# ones likely to actually occur in the real world; names in a File are NOT permitted to end
# with a dot, contain 2 dots in a row, contain spaces, etc.
File
    Dir NoWhitespace PathName

Dir
    "/" NoWhitespace DirElements-opt
    "./" NoWhitespace DirElements-opt
    "../" NoWhitespace DirElements-opt

DirElements
    DirElement
    DirElements NoWhitespace DirElement

DirElement
    "../"
    PathName NoWhitespace "/"

PathName
    "."-opt NoWhitespace PathNameParts          # allows UNIX-style hidden files, e.g. ".gitignore"

PathNameParts
    PathNamePart
    PathNameParts NoWhitespace "." NoWhitespace PathNamePart

PathNamePart
    IdentifierTrails

IdentifierTrails
    IdentifierTrail
    IdentifierTrails IdentifierTrail

IdentifierTrail
    # defined by the Ecstasy spec as Unicode categories Lu Ll Lt Lm Lo Mn Mc Me Nd Nl No Sc plus U+005F

#
# types
#

TypeExpression
    UnionedTypeExpression

# '+' creates a union of two types; '-' creates a difference of two types
UnionedTypeExpression
    IntersectingTypeExpression
    UnionedTypeExpression + IntersectingTypeExpression
    UnionedTypeExpression - IntersectingTypeExpression

IntersectingTypeExpression
    NonBiTypeExpression
    IntersectingTypeExpression | NonBiTypeExpression

NonBiTypeExpression
    "(" TypeExpression ")"
    AnnotatedTypeExpression
    NamedTypeExpression
    FunctionTypeExpression
    NonBiTypeExpression NoWhitespace "?"
    NonBiTypeExpression ArrayDims
    NonBiTypeExpression ArrayIndexes             # ArrayIndexes is not consumed by this construction
    NonBiTypeExpression "..."
    "immutable" NonBiTypeExpression

AnnotatedTypeExpression
    Annotation TypeExpression

NamedTypeExpression
    QualifiedName TypeAccessModifier-opt NoAutoNarrowModifier-opt TypeParameterTypeList-opt

TypeAccessModifier
    NoWhitespace ":" NoWhitespace AccessModifier

NoAutoNarrowModifier
    NoWhitespace "!"

# Note: in the case that the name precedes the ParameterTypeList, the token
#       stream is re-ordered such that the name is deposited into the stream
#       after the ParameterTypeList, and is not consumed by this construction
FunctionTypeExpression
    "function" ReturnList Name-opt "(" TypeExpressionList-opt ")"

