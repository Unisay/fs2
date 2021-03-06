package fs2

import fs2.internal.Resources
import fs2.util.{Async,Attempt,Catenable,Eq,Free,NonFatal,Sub1,~>,RealSupertype}
import fs2.util.syntax._
import StreamCore.{Env,NT,Stack,Token}

private[fs2] sealed trait StreamCore[F[_],O] { self =>
  type O0

  def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]): Scope[G,Stack[G,O0,O2]]
  final def pushEmit(c: Chunk[O]): StreamCore[F,O] = if (c.isEmpty) this else StreamCore.append(StreamCore.chunk(c), self)

  def render: String

  final override def toString = render

  final def attempt: StreamCore[F,Attempt[O]] =
    self.map(a => Right(a): Attempt[O]).onError(e => StreamCore.emit(Left(e)))

  final def translate[G[_]](f: NT[F,G]): StreamCore[G,O] =
    f.same.fold(sub => Sub1.substStreamCore(self)(sub), f => {
      new StreamCore[G,O] {
        type O0 = self.O0
        def push[G2[_],O2](u: NT[G,G2], stack: Stack[G2,O,O2]): Scope[G2,Stack[G2,O0,O2]] =
          self.push(NT.T(f) andThen u, stack)
        def render = s"$self.translate($f)"
      }
    })

  // proof that this is sound - `translate`
  final def covary[F2[_]](implicit S: Sub1[F,F2]): StreamCore[F2,O] =
    self.asInstanceOf[StreamCore[F2,O]]
  // proof that this is sound - `mapChunks`
  final def covaryOutput[O2>:O](implicit T: RealSupertype[O,O2]): StreamCore[F,O2] =
    self.asInstanceOf[StreamCore[F,O2]]

  final def flatMap[O2](f: O => StreamCore[F,O2]): StreamCore[F,O2] =
    new StreamCore[F,O2] { type O0 = self.O0
      def push[G[_],O3](u: NT[F,G], stack: Stack[G,O2,O3]) =
        Scope.suspend { self.push(u, stack pushBind NT.convert(f)(u)) }
      def render = s"$self.flatMap(<function1>)"
    }
  final def map[O2](f: O => O2): StreamCore[F,O2] = mapChunks(_ map f)
  final def mapChunks[O2](f: Chunk[O] => Chunk[O2]): StreamCore[F,O2] =
    new StreamCore[F,O2] { type O0 = self.O0
      def push[G[_],O3](u: NT[F,G], stack: Stack[G,O2,O3]) =
        self.push(u, stack pushMap f)
      def render = s"$self.mapChunks(<function1>)"
    }
  final def onError(f: Throwable => StreamCore[F,O]): StreamCore[F,O] =
    new StreamCore[F,O] { type O0 = self.O0
      def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
        Scope.suspend { self.push(u, stack pushHandler NT.convert(f)(u)) }
      def render = s"$self.onError(<function1>)"
    }

  final def drain[O2]: StreamCore[F,O2] = self.flatMap(_ => StreamCore.empty)

  final def onComplete(s2: StreamCore[F,O]): StreamCore[F,O] =
    StreamCore.append(self onError (e => StreamCore.append(s2, StreamCore.fail(e))), s2)

  final def step: Scope[F, Option[Attempt[(NonEmptyChunk[O],StreamCore[F,O])]]]
    = push(NT.Id(), Stack.empty[F,O]) flatMap StreamCore.step

  final def runFold[O2](z: O2)(f: (O2,O) => O2): Free[F,O2] =
    runFoldScope(z)(f)
      .bindEnv(Env(Resources.empty[Token,Free[F,Attempt[Unit]]], () => false))
      .map(_._2)

  final def runFoldScope[O2](z: O2)(f: (O2,O) => O2): Scope[F,O2] = step flatMap {
    case None => Scope.pure(z)
    case Some(Left(err)) => Scope.fail(err)
    case Some(Right((hd,tl))) =>
      try tl.runFoldScope(hd.foldLeft(z)(f))(f)
      catch { case NonFatal(e) => Scope.fail(e) }
  }

  final def uncons: StreamCore[F, Option[(NonEmptyChunk[O], StreamCore[F,O])]] =
    StreamCore.evalScope(step) flatMap {
      case None => StreamCore.emit(None)
      case Some(Left(err)) => StreamCore.fail(err)
      case Some(Right(s)) => StreamCore.emit(Some(s))
    }

  final def fetchAsync(implicit F: Async[F]): Scope[F, ScopedFuture[F,StreamCore[F,O]]] =
    unconsAsync map { f => f map { case (leftovers,o) =>
      val inner: StreamCore[F,O] = o match {
        case None => StreamCore.empty
        case Some(Left(err)) => StreamCore.fail(err)
        case Some(Right((hd, tl))) => StreamCore.append(StreamCore.chunk(hd), tl)
      }
      if (leftovers.isEmpty) inner else StreamCore.release(leftovers) flatMap { _ => inner }
    }}

  final def unconsAsync(implicit F: Async[F])
  : Scope[F,ScopedFuture[F, (List[Token], Option[Attempt[(NonEmptyChunk[O],StreamCore[F,O])]])]]
  = Scope.eval(F.ref[(List[Token], Option[Attempt[(NonEmptyChunk[O],StreamCore[F,O])]])]).flatMap { ref =>
    val token = new Token()
    val resources = Resources.emptyNamed[Token,Free[F,Attempt[Unit]]]("unconsAsync")
    val noopWaiters = scala.collection.immutable.Stream.continually(() => ())
    lazy val rootCleanup: Free[F,Attempt[Unit]] = Free.suspend { resources.closeAll(noopWaiters) match {
      case Left(waiting) =>
        Free.eval(Vector.fill(waiting)(F.ref[Unit]).sequence) flatMap { gates =>
          resources.closeAll(gates.toStream.map(gate => () => F.unsafeRunAsync(gate.setPure(()))(_ => ()))) match {
            case Left(_) => Free.eval(gates.traverse(_.get)) flatMap { _ =>
              resources.closeAll(noopWaiters) match {
                case Left(_) => println("likely FS2 bug - resources still being acquired after Resources.closeAll call")
                                rootCleanup
                case Right(resources) => StreamCore.runCleanup(resources.map(_._2))
              }
            }
            case Right(resources) =>
              StreamCore.runCleanup(resources.map(_._2))
          }
        }
        case Right(resources) =>
          StreamCore.runCleanup(resources.map(_._2))
    }}
    def tweakEnv: Scope[F,Unit] =
      Scope.startAcquire(token) flatMap { _ =>
      Scope.finishAcquire(token, rootCleanup) flatMap { ok =>
        if (ok) Scope.pure(())
        else Scope.evalFree(rootCleanup).flatMap(_.fold(Scope.fail, Scope.pure))
      }}
    val s: F[Unit] = ref.set { step.bindEnv(StreamCore.Env(resources, () => resources.isClosed)).run }
    tweakEnv.flatMap { _ =>
      Scope.eval(s) map { _ =>
        ScopedFuture.readRef(ref).appendOnForce { Scope.suspend {
          // Important: copy any locally acquired resources to our parent and remove the placeholder
          // root token, which only needed if the parent terminated early, before the future was forced
          val removeRoot = Scope.release(List(token)) flatMap { _.fold(Scope.fail, Scope.pure) }
          (resources.closeAll(scala.collection.immutable.Stream()) match {
            case Left(_) => Scope.fail(new IllegalStateException("FS2 bug: resources still being acquired"))
            case Right(rs) => removeRoot flatMap { _ => Scope.traverse(rs) {
              case (token,r) => Scope.acquire(token,r)
            }}
          }) flatMap { (rs: List[Attempt[Unit]]) =>
            rs.collect { case Left(e) => e } match {
              case Nil => Scope.pure(())
              case e :: _ => Scope.fail(e)
            }
          }
        }}
      }
    }
  }
}

private[fs2] object StreamCore {

  final class Token {
    override def toString = s"Token(${##})"
  }

  final case class Env[F[_]](tracked: Resources[Token,Free[F,Attempt[Unit]]], interrupted: () => Boolean)

  trait AlgebraF[F[_]] { type f[x] = Algebra[F,x] }

  sealed trait Algebra[+F[_],+A]
  object Algebra {
    final case class Eval[F[_],A](f: F[A]) extends Algebra[F,A]
    final case object Interrupted extends Algebra[Nothing,Boolean]
    final case object Snapshot extends Algebra[Nothing,Set[Token]]
    final case class NewSince(snapshot: Set[Token]) extends Algebra[Nothing,List[Token]]
    final case class Release(tokens: List[Token]) extends Algebra[Nothing,Attempt[Unit]]
    final case class StartAcquire(token: Token) extends Algebra[Nothing,Boolean]
    final case class FinishAcquire[F[_]](token: Token, cleanup: Free[F,Attempt[Unit]]) extends Algebra[F,Boolean]
    final case class CancelAcquire(token: Token) extends Algebra[Nothing,Unit]
  }

  sealed trait NT[-F[_],+G[_]] {
    val same: Either[Sub1[F,G], F ~> G]
    def andThen[H[_]](f: NT[G,H]): NT[F,H]
  }

  object NT {
    final case class Id[F[_]]() extends NT[F,F] {
      val same = Left(Sub1.sub1[F])
      def andThen[H[_]](f: NT[F,H]): NT[F,H] = f
    }
    final case class T[F[_],G[_]](u: F ~> G) extends NT[F,G] {
      val same = Right(u)
      def andThen[H[_]](f: NT[G,H]): NT[F,H] = f.same.fold(
        f => T(Sub1.substUF1(u)(f)),
        f => T(u andThen f)
      )
    }
    def convert[F[_],G[_],O](s: StreamCore[F,O])(u: NT[F,G]): StreamCore[G,O] =
      u.same match {
        case Left(sub) => Sub1.substStreamCore(s)(sub)
        case Right(u) => s.translate(NT.T(u))
      }
    def convert[F[_],G[_],O1,O2](f: O1 => StreamCore[F,O2])(u: NT[F,G]): O1 => StreamCore[G,O2] =
      u.same match {
        case Left(sub) => Sub1.substStreamCoreF(f)(sub)
        case Right(u) => o1 => f(o1).translate(NT.T(u))
      }
    def convert[F[_],G[_],O](s: Segment[F,O])(u: NT[F,G]): Segment[G,O] =
      u.same match {
        case Left(sub) => Sub1.substSegment(s)(sub)
        case Right(u) => s.translate(NT.T(u))
      }
    def convert[F[_],G[_],O](s: Catenable[Segment[F,O]])(u: NT[F,G]): Catenable[Segment[G,O]] = {
      type f[g[_],x] = Catenable[Segment[g,x]]
      u.same match {
        case Left(sub) => Sub1.subst[f,F,G,O](s)(sub)
        case Right(_) => s.map(_ translate u)
      }
    }
    def convert[F[_],G[_],O](s: Scope[F,O])(u: NT[F,G]): Scope[G,O] =
      u.same match {
        case Left(sub) => Sub1.subst[Scope,F,G,O](s)(sub)
        case Right(u) => s translate u
      }
    def convert[F[_],G[_],O](f: F[O])(u: NT[F,G]): G[O] =
      u.same match {
        case Left(sub) => sub(f)
        case Right(u) => u(f)
      }
  }

  final case object Interrupted extends Exception { override def fillInStackTrace = this }

  private[fs2] def attemptStream[F[_],O](s: => StreamCore[F,O]): StreamCore[F,O] =
    try s catch { case NonFatal(e) => fail(e) }

  def step[F[_],O0,O](stack: Stack[F,O0,O]): Scope[F,Option[Attempt[(NonEmptyChunk[O],StreamCore[F,O])]]] =
    Scope.interrupted.flatMap { interrupted =>
      if (interrupted) Scope.pure(Some(Left(Interrupted)))
      else {
        stack.fold(new Stack.Fold[F,O0,O,Scope[F,Option[Attempt[(NonEmptyChunk[O],StreamCore[F,O])]]]] {
          def unbound(segs: Catenable[Segment[F,O0]], eq: Eq[O0, O]) = {
            Eq.subst[({ type f[x] = Catenable[Segment[F,x]] })#f, O0, O](segs)(eq).uncons match {
              case None => Scope.pure(None)
              case Some((hd, segs)) => hd match {
                case Segment.Fail(err) => Stack.fail[F,O](segs)(err) match {
                  case Left(err) => Scope.pure(Some(Left(err)))
                  case Right((s, segs)) => step(Stack.segments(segs).pushAppend(s))
                }
                case Segment.Emit(chunk) =>
                  if (chunk.isEmpty) step(Stack.segments(segs))
                  else Scope.pure(Some(Right((NonEmptyChunk.fromChunkUnsafe(chunk), StreamCore.segments(segs)))))
                case Segment.Handler(h) => step(Stack.segments(segs))
                case Segment.Append(s) => s.push(NT.Id(), Stack.segments(segs)) flatMap step
              }
            }
          }

          def map[X](segs: Catenable[Segment[F,O0]], f: Chunk[O0] => Chunk[X], stack: Stack[F,X,O]): Scope[F,Option[Attempt[(NonEmptyChunk[O],StreamCore[F,O])]]] = {
            segs.uncons match {
              case None => step(stack)
              case Some((hd, segs)) => hd match {
                case Segment.Emit(chunk) =>
                  val segs2 = segs.map(_.mapChunks(f))
                  val stack2 = stack.pushSegments(segs2)
                  step(try { stack2.pushEmit(f(chunk)) } catch { case NonFatal(e) => stack2.pushFail(e) })
                case Segment.Append(s) =>
                  s.push(NT.Id(), stack.pushMap(f).pushSegments(segs)) flatMap step
                case Segment.Fail(err) => Stack.fail(segs)(err) match {
                  case Left(err) => step(stack.pushFail(err))
                  case Right((hd, segs)) => step(stack.pushMap(f).pushSegments(segs).pushAppend(hd))
                }
                case Segment.Handler(_) => step(stack.pushMap(f).pushSegments(segs))
              }
            }
          }

          def bind[X](segs: Catenable[Segment[F,O0]], f: O0 => StreamCore[F,X], stack: Stack[F,X,O]): Scope[F,Option[Attempt[(NonEmptyChunk[O],StreamCore[F,O])]]] = {
            segs.uncons match {
              case None => step(stack)
              case Some((hd, segs)) => hd match {
                case Segment.Emit(chunk) =>
                  chunk.uncons match {
                    case None => step(stack.pushBind(f).pushSegments(segs))
                    case Some((hd,tl)) => step({
                      val segs2: Catenable[Segment[F,X]] =
                        (if (tl.isEmpty) segs else segs.push(Segment.Emit(tl))).map(_.interpretBind(f))
                      val stack2 = stack.pushSegments(segs2)
                      try stack2.pushAppend(f(hd)) catch { case NonFatal(t) => stack2.pushFail(t) }
                    })
                  }
                case Segment.Append(s) =>
                  s.push(NT.Id(), stack.pushBind(f).pushSegments(segs)) flatMap step
                case Segment.Fail(err) => Stack.fail(segs)(err) match {
                  case Left(err) => step(stack.pushFail(err))
                  case Right((hd, segs)) => step(stack.pushBind(f).pushSegments(segs).pushAppend(hd))
                }
                case Segment.Handler(_) => step(stack.pushBind(f).pushSegments(segs))
              }}
            }
          }
      )}
    }

  private def segment[F[_],O](s: Segment[F,O]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
      Scope.pure { stack push (NT.convert(s)(u)) }
    def render = s"Segment($s)"
  }

  private def segments[F[_],O](s: Catenable[Segment[F,O]]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
      Scope.pure { stack pushSegments (NT.convert(s)(u)) }
    def render = "Segments(" + s.toStream.toList.mkString(", ") + ")"
  }

  def scope[F[_],O](s: StreamCore[F,O]): StreamCore[F,O] = StreamCore.evalScope(Scope.snapshot).flatMap { tokens =>
    s onComplete {
      // release any newly acquired resources since the snapshot taken before starting `s`
      StreamCore.evalScope(Scope.newSince(tokens)) flatMap { acquired =>
        StreamCore.evalScope(Scope.release(acquired)).drain
      }
    }
  }

  def acquire[F[_],R](r: F[R], cleanup: R => Free[F,Unit]): StreamCore[F,(Token,R)] = StreamCore.suspend {
    val token = new Token()
    StreamCore.evalScope(Scope.startAcquire(token)) flatMap { _ =>
      StreamCore.attemptEval(r).flatMap {
        case Left(e) => StreamCore.evalScope(Scope.cancelAcquire(token)) flatMap { _ => StreamCore.fail(e) }
        case Right(r) =>
          StreamCore.evalScope(Scope.finishAcquire(token, Free.suspend(cleanup(r).attempt)))
                    .flatMap { _ => StreamCore.emit((token,r)).onComplete(StreamCore.release(List(token)).drain) }
      }
    }
  }

  def release[F[_]](tokens: List[Token]): StreamCore[F,Unit] =
    evalScope(Scope.release(tokens)) flatMap { _ fold(fail, emit) }

  def evalScope[F[_],O](s: Scope[F,O]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
      NT.convert(s)(u) map { o => stack push (Segment.Emit(Chunk.singleton(o))) }
    def render = "evalScope(<scope>)"
  }

  def chunk[F[_],O](c: Chunk[O]): StreamCore[F,O] = segment(Segment.Emit[F,O](c))
  def emit[F[_],O](w: O): StreamCore[F,O] = chunk(Chunk.singleton(w))
  def empty[F[_],O]: StreamCore[F,O] = chunk(Chunk.empty)
  def fail[F[_],O](err: Throwable): StreamCore[F,O] = segment(Segment.Fail[F,O](err))
  def attemptEval[F[_],O](f: F[O]): StreamCore[F,Attempt[O]] = new StreamCore[F,Attempt[O]] {
    type O0 = Attempt[O]
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,Attempt[O],O2]) =
      Scope.attemptEval(NT.convert(f)(u)) map { o => stack push Segment.Emit(Chunk.singleton(o)) }
    def render = s"attemptEval($f)"
  }
  def eval[F[_],O](f: F[O]): StreamCore[F,O] = attemptEval(f) flatMap { _ fold(fail, emit) }

  def append[F[_],O](s: StreamCore[F,O], s2: StreamCore[F,O]): StreamCore[F,O] =
    new StreamCore[F,O] {
      type O0 = s.O0
      def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
        Scope.suspend { s.push(u, stack push Segment.Append(s2 translate u)) }
       def render = s"append($s, $s2)"
    }
  def suspend[F[_],O](s: => StreamCore[F,O]): StreamCore[F,O] = emit(()) flatMap { _ => s }

  sealed trait Segment[F[_],O1] {
    import Segment._

    final def translate[G[_]](u: NT[F,G]): Segment[G,O1] = u.same match {
      case Left(sub) => Sub1.substSegment(this)(sub)
      case Right(uu) => this match {
        case Append(s) => Append(s translate u)
        case Handler(h) => Handler(NT.convert(h)(u))
        case Emit(c) => this.asInstanceOf[Segment[G,O1]]
        case Fail(e) => this.asInstanceOf[Segment[G,O1]]
      }
    }

    final def mapChunks[O2](f: Chunk[O1] => Chunk[O2]): Segment[F, O2] = this match {
      case Append(s) => Append(s.mapChunks(f))
      case Handler(h) => Handler(t => h(t).mapChunks(f))
      case Emit(c) => Emit(f(c))
      case Fail(e) => this.asInstanceOf[Segment[F,O2]]
    }

    final def interpretBind[O2](f: O1 => StreamCore[F, O2]): Segment[F, O2] = this match {
      case Append(s) => Append(s.flatMap(f))
      case Handler(h) => Handler(t => h(t).flatMap(f))
      case Emit(c) => if (c.isEmpty) this.asInstanceOf[Segment[F,O2]] else Append(c.toVector.map(o => attemptStream(f(o))).reduceRight((s, acc) => StreamCore.append(s, acc)))
      case Fail(e) => this.asInstanceOf[Segment[F,O2]]
    }
  }
  object Segment {
    final case class Fail[F[_],O1](err: Throwable) extends Segment[F,O1]
    final case class Emit[F[_],O1](c: Chunk[O1]) extends Segment[F,O1]
    final case class Handler[F[_],O1](h: Throwable => StreamCore[F,O1]) extends Segment[F,O1] {
      override def toString = "Handler"
    }
    final case class Append[F[_],O1](s: StreamCore[F,O1]) extends Segment[F,O1]
  }

  trait Stack[F[_],O1,O2] { self =>
    def fold[R](fold: Stack.Fold[F,O1,O2,R]): R

    def render: List[String]

    def pushHandler(f: Throwable => StreamCore[F,O1]) = push(Segment.Handler(f))
    def pushEmit(s: Chunk[O1]) = push(Segment.Emit(s))
    def pushFail(e: Throwable) = push(Segment.Fail(e))
    def pushAppend(s: StreamCore[F,O1]) = push(Segment.Append(s))
    def pushBind[O0](f: O0 => StreamCore[F,O1]): Stack[F,O0,O2] = new Stack[F,O0,O2] {
      def fold[R](fold: Stack.Fold[F,O0,O2,R]): R = fold.bind(Catenable.empty, f, self)
      def render = "Bind" :: self.render
    }
    def pushMap[O0](f: Chunk[O0] => Chunk[O1]): Stack[F,O0,O2] = new Stack[F,O0,O2] {
      def fold[R](fold: Stack.Fold[F,O0,O2,R]): R = fold.map(Catenable.empty, f, self)
      def render = "Map" :: self.render
    }
    def push(s: Segment[F,O1]): Stack[F,O1,O2] = s match {
      case Segment.Emit(c) if c.isEmpty => this
      case _ => self.fold(new Stack.Fold[F,O1,O2,Stack[F,O1,O2]] {
        def unbound(segments: Catenable[Segment[F,O1]], eq: Eq[O1, O2]) =
          Eq.subst[({type f[x] = Stack[F,O1,x] })#f, O1, O2](Stack.segments(s :: segments))(eq)
        def map[X](segments: Catenable[Segment[F,O1]], f: Chunk[O1] => Chunk[X], stack: Stack[F,X,O2]) =
          stack.pushMap(f).pushSegments(s :: segments)
        def bind[X](segments: Catenable[Segment[F,O1]], f: O1 => StreamCore[F,X], stack: Stack[F,X,O2]) =
          stack.pushBind(f).pushSegments(s :: segments)
      })
    }
    def pushSegments(s: Catenable[Segment[F,O1]]): Stack[F,O1,O2] =
      if (s.isEmpty) self
      else new Stack[F,O1,O2] {
        def render = Stack.describeSegments(s) :: self.render
        def fold[R](fold: Stack.Fold[F,O1,O2,R]): R =
          self.fold(new Stack.Fold[F,O1,O2,R] {
            def unbound(segments: Catenable[Segment[F,O1]], eq: Eq[O1, O2]) =
              if (segments.isEmpty) fold.unbound(s, eq) // common case
              else fold.unbound(s ++ segments, eq)
            def map[X](segments: Catenable[Segment[F,O1]], f: Chunk[O1] => Chunk[X], stack: Stack[F,X,O2]) =
              fold.map(s ++ segments, f, stack)
            def bind[X](segments: Catenable[Segment[F,O1]], f: O1 => StreamCore[F,X], stack: Stack[F,X,O2]) =
              fold.bind(s ++ segments, f, stack)
          })
      }
  }

  object Stack {
    trait Fold[F[_],O1,O2,+R] {
      def unbound(segments: Catenable[Segment[F,O1]], eq: Eq[O1, O2]): R
      def map[X](segments: Catenable[Segment[F,O1]], f: Chunk[O1] => Chunk[X], stack: Stack[F,X,O2]): R
      def bind[X](segments: Catenable[Segment[F,O1]], f: O1 => StreamCore[F,X], stack: Stack[F,X,O2]): R
    }

    def empty[F[_],O1]: Stack[F,O1,O1] = segments(Catenable.empty)

    def segments[F[_],O1](s: Catenable[Segment[F,O1]]): Stack[F,O1,O1] = new Stack[F,O1,O1] {
      def fold[R](fold: Fold[F,O1,O1,R]): R = fold.unbound(s, Eq.refl)
      def render = List(describeSegments(s))
    }

    private[fs2]
    def describeSegments[F[_],O](s: Catenable[Segment[F,O]]): String = {
      val segments = s.toStream.toList
      s"Segments (${segments.size})\n"+segments.zipWithIndex.map { case (s, idx) => s"    s$idx: $s" }.mkString("\n")
    }

    @annotation.tailrec
    def fail[F[_],O1](s: Catenable[Segment[F,O1]])(err: Throwable)
    : Attempt[(StreamCore[F,O1], Catenable[Segment[F,O1]])]
    = s.uncons match {
      case None => Left(err)
      case Some((Segment.Handler(f),tl)) => Right(attemptStream(f(err)) -> tl)
      case Some((_, tl)) => fail(tl)(err)
    }
  }

  private
  def runCleanup[F[_]](l: Resources[Token,Free[F,Attempt[Unit]]]): Free[F,Attempt[Unit]] =
    l.closeAll(scala.collection.immutable.Stream()) match {
      case Right(l) => runCleanup(l.map(_._2))
      case Left(_) => sys.error("internal FS2 error: cannot run cleanup actions while resources are being acquired: "+l)
    }

  private[fs2]
  def runCleanup[F[_]](cleanups: Iterable[Free[F,Attempt[Unit]]]): Free[F,Attempt[Unit]] = {
    // note - run cleanup actions in FIFO order, but avoid left-nesting flatMaps
    // all actions are run but only first error is reported
    cleanups.toList.reverse.foldLeft[Free[F,Attempt[Unit]]](Free.pure(Right(())))(
      (tl,hd) => hd flatMap { _.fold(e => tl flatMap { _ => Free.pure(Left(e)) }, _ => tl) }
    )
  }
}
