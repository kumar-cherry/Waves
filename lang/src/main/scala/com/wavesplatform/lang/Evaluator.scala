package com.wavesplatform.lang

import com.wavesplatform.lang.Terms._
import scodec.bits.ByteVector
import scorex.crypto.signatures.Curve25519

import scala.util.{Failure, Success, Try}

object Evaluator {

  import scala.util.control.TailCalls.{TailRec, done, tailcall}

  type Defs = Map[String, (Type, Any)]

  case class Context(domain: Domain, defs: Defs)

  type TypeResolutionError = String
  type ExcecutionError = String
  type ExecResult[T] = Either[ExcecutionError, T]

  def resolveType(defs: Defs, t: Expr): TailRec[Either[TypeResolutionError, Type]] = t match {
    case REF(key) => done(defs.get(key).map(_._1).toRight(s"Typecheck failed: Cannot resolve type of $key"))
    case Block(maybeLet, expr) => tailcall {
      maybeLet match {
        case Some(let) =>
          resolveType(defs, let.value)
            .flatMap(x => x.fold(fa => done(Left(fa)),
              innerType => resolveType(defs + (let.name -> (innerType, null)), expr)))
        case None => resolveType(defs, expr)
      }
    }
    case IF(_, l, r) => tailcall {
      for {
        lType <- resolveType(defs, l)
        rType <- resolveType(defs, r)
      } yield {
        lType.flatMap(t1 => rType.map(t2 => eqType(t1,t2)))
          .fold(fa => Left(fa), x => if (x.isDefined) Right(x.get) else Left(s"Typecheck failed for IF: RType($rType) differs from LType($lType)"))
      }
    }
    case EQ(l,r) => tailcall {
      for {
        lType <- resolveType(defs, l)
        rType <- resolveType(defs, r)
      } yield {
        rType.flatMap(t1 => lType.map(t2 => eqType(t1,t2).isDefined))
          .fold(fa => Left(fa), x => if (x) Right(BOOLEAN) else Left(s"Typecheck failed for EQ: RType($rType) differs from LType($lType)"))
      }
    }

    case get: GET => tailcall {
      resolveType(defs, get.t) flatMap {
        case Right(OPTION(in)) => done(Right(in))
        case Right(x) => done(Left(s"Typecheck failed: GET called on $x, but only call on OPTION[_] is allowed"))
        case Left(err) => done(Left(s"Typecheck failed: $err"))
      }
    }
    case SOME(b) => tailcall{
      resolveType(defs, b) flatMap {
        case Right(tpe) => done(Right(OPTION(tpe)))
        case Left(err) => done(Left(s"Typecheck failed: $err"))
      }
    }
    case x => done(Right(x.predefinedType.get))
  }

  private def r[T](ctx: Context, t: Expr): TailRec[ExecResult[T]] = {
    (t match {
      case Block(maybelet, inner) => tailcall {
        maybelet match {
          case None => resolveType(ctx.defs, inner).flatMap(termType => {
            termType.fold(fa => done(Left(fa)), t => r[t.Underlying](ctx, inner))
          })
          case Some(LET(newVarName: String, newVarExpr: Block)) => {
            resolveType(ctx.defs, newVarExpr).flatMap(newVarType => {
              Either.cond(ctx.defs.get(newVarName).isEmpty, (), s"Value '$newVarName' already defined in the scope").flatMap(_ => newVarType)
                .fold(fa => done(Left(fa)), t => r[t.Underlying](ctx, newVarExpr)
                  .flatMap(newVarValue => {
                    newVarValue.fold(fa => done(Left(fa)), v => {
                      val newDefs = ctx.defs + (newVarName -> (t, v))
                      resolveType(newDefs, inner).flatMap(termType => {
                        termType.fold(fa => done(Left(fa)), tt =>
                          r[tt.Underlying](ctx.copy(defs = newDefs), inner))
                      })
                    })
                  }))
            })
          }
        }
      }
      case LET(name, v) => tailcall {
        resolveType(ctx.defs, v).flatMap(rType =>
          rType.fold(fa => done(Left(fa)), t => r[t.Underlying](ctx, v)))
      }
      case REF(str) => done {
        ctx.defs.get(str) match {
          case Some((x, y)) => Right(y.asInstanceOf[x.Underlying])
          case None => Left(s"Definition '$str' not found")
        }
      }
      case CONST_INT(v) => done(Right(v))
      case CONST_BYTEVECTOR(v) => done(Right(v))
      case TRUE => done(Right(true))
      case FALSE => done(Right(false))
      case SUM(t1, t2) => tailcall {
        for {
          a1 <- r[Int](ctx, t1)
          a2 <- r[Int](ctx, t2)
        } yield a1.flatMap(v1 => a2.map(v2 => v1 + v2))
      }
      case GE(t1, t2) => tailcall {
        for {
          a1 <- r[Int](ctx, t1)
          a2 <- r[Int](ctx, t2)
        } yield a1.flatMap(v1 => a2.map(v2 => v1 >= v2))
      }
      case GT(t1, t2) => tailcall {
        for {
          a1 <- r[Int](ctx, t1)
          a2 <- r[Int](ctx, t2)
        } yield a1.flatMap(v1 => a2.map(v2 => v1 > v2))
      }
      case i@IF(cond, t1, t2) => tailcall {
        resolveType(ctx.defs, i).flatMap {
          case Right(_) =>
            r[Boolean](ctx, cond) flatMap {
              case Right(true) => r(ctx, t1)
              case Right(false) => r(ctx, t2)
              case Left(err) => done(Left(err))
            }
          case Left(err) => done(Left(err))
        }
      }
      case AND(t1, t2) => tailcall {
        r[Boolean](ctx, t1) flatMap {
          case Left(err) => done(Left(err))
          case Right(false) => done(Right(false))
          case Right(true) =>
            r[Boolean](ctx, t2) map {
              case Left(err) => Left(err)
              case Right(v) => Right(v)
            }
        }
      }
      case o@OR(t1, t2) => tailcall {
        r[Boolean](ctx, t1) flatMap {
          case Left(err) => done(Left(err))
          case Right(true) => done(Right(true))
          case Right(false) =>
            r[Boolean](ctx, t2) map {
              case Left(err) => Left(err)
              case Right(v) => Right(v)
            }
        }
      }
      case HEIGHT => done(ctx.domain.height)
      case TX_FIELD(f) => done {
        f match {
          case Id => ctx.domain.id
          case Type => ctx.domain.tpe
          case SenderPk => ctx.domain.senderPk
          case Proof(i) => ctx.domain.proof(i)
          case BodyBytes => ctx.domain.bodyBytes
          case _ => ??? // match for  __satisfy_shapeless_0
        }
      }
      case IS_DEFINED(opt) => tailcall {
        resolveType(ctx.defs, opt).flatMap(optType => optType.fold(fa => done(Left(fa)), t => {
          r[t.Underlying](ctx, opt).map {
            case Right(x: Option[_]) =>
              x match {
                case Some(xx) => Right(true)
                case None => Right(false)
              }
            case Left(_) => Left("IS_DEFINED invoked on non-option type")
            case _ => Left("IS_DEFINED expression error")
          }
        }))
      }

      case GET(opt) => tailcall {
        resolveType(ctx.defs, opt).flatMap(optType => optType.fold(fa => done(Left(fa)), t => {
          r[t.Underlying](ctx, opt).map {
            case Right(x: Option[_]) =>
              x match {
                case Some(xx) => Right(xx)
                case None => Left("get(NONE)")
              }
            case Left(_) => Left("GET invoked on non-option type")
            case _ => Left("GET expression error")
          }
        }))
      }
      case NONE => done(Right(None))
      case SOME(b) =>tailcall {
        resolveType(ctx.defs, b).flatMap {
          case Right(tpe) => r[tpe.Underlying](ctx,b).map(_.map(x=>Some(x)))
          case Left(err) => done(Left(err))
        }
      }
      case eq@EQ(it1, it2) => tailcall {
        resolveType(ctx.defs, eq).flatMap {
          case Right(tpe) =>
            for {
              i1 <- r[tpe.Underlying](ctx, it1)
              i2 <- r[tpe.Underlying](ctx, it2)
            } yield i1.flatMap(v1 => i2.map(v2 => v1 == v2))
          case Left(err) => done(Left(err))
        }
      }
      case SIG_VERIFY(msg, sig, pk) => tailcall {
        for {
          s <- r[ByteVector](ctx, sig)
          m <- r[ByteVector](ctx, msg)
          p <- r[ByteVector](ctx, pk)
        } yield s.flatMap(ss => m.flatMap(mm => p.map(pp => Curve25519.verify(ss.toArray, mm.toArray, pp.toArray))))
      }
    }).map(x => x.map(_.asInstanceOf[T]))
  }

  def apply[A](c: Context, term: Expr): ExecResult[A] = {
    lazy val result = r[A](c, term).result
//    Try(result) match {
//      case Failure(ex) => Left(ex.toString)
//      case Success(res) => res
//    }
    result
  }
}
