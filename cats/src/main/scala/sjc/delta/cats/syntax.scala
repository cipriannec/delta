package sjc.delta.cats

import scala.language.higherKinds

import sjc.delta.Delta
import sjc.delta.Delta.Aux

import cats.Apply


object syntax {
  implicit class DeltaCatsOps[A, B](val value: Aux[A, B]) extends AnyVal {
    def liftA[F[_]: Apply]: Aux[F[A], F[B]] = new DeltaApply[F, A, B](value)
  }

  private case class DeltaApply[F[_]: Apply, A, B](delta: Aux[A, B]) extends Delta[F[A]] {
    type Out = F[B]
    def apply(leftF: F[A], rightF: F[A]): F[B] = Apply[F].map2(leftF, rightF)(delta.apply)
  }
}