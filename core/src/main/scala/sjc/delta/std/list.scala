package sjc.delta.std

import sjc.delta.{Delta, Patch}
import sjc.delta.util.DeltaListOps

import scala.annotation.tailrec
import scala.collection.{mutable ⇒ M}
import scala.util.Random


object list {
  object naive {
    implicit def deltaList[A, B](
      implicit deltaA: Delta.Aux[A, B], patchB: Patch[B]
    ): Delta.Aux[List[A], List[Change[A, B]]] = new Delta[List[A]] {
      type Out = List[Change[A, B]]

      def apply(left: List[A], right: List[A]): Out = {
        val (zipped, unzipped) = left.zipExact(right)

        val diffs: List[Change[A, B]] = zipped.zipWithIndex.flatMap {
          case ((lhs, rhs), index) ⇒ {
            val diff = deltaA(lhs, rhs)

            if (patchB.isEmpty(diff)) None else Some(Diff(index, diff))
          }
        }

        val zipLength = zipped.length

        unzipped match {
          case None ⇒ diffs
          case Some(Left(missing)) ⇒ diffs ++ missing.zipWithIndex.map { case (m, index) ⇒ Missing(index + zipLength, m) }
          case Some(Right(extra))  ⇒ diffs ++ extra.zipWithIndex.map   { case (e, index) ⇒ Extra(index + zipLength, e)   }
        }
      }
    }

    trait Change[+A, +B]
    case class Diff[B](index: Int, diff: B)           extends Change[Nothing, B]
    case class Extra[A](index: Int, extra: A)         extends Change[A, Nothing]
    case class Missing[A](index: Int, missing: A)     extends Change[A, Nothing]
  }

  object patience {
    implicit def deltaList[A]: Delta.Aux[List[A], List[Change[A]]] = new ListDelta[A]

    sealed trait Change[A]
    case class Equal[A](subSeq: SubSeq, subSequence: List[A])                   extends Change[A]
    case class Inserted[A](subSeq: SubSeq, inserted: List[A])                   extends Change[A]
    case class Replaced[A](subSeq: SubSeq, removed: List[A], inserted: List[A]) extends Change[A]
    case class Removed[A](subSeq: SubSeq, removed: List[A])                     extends Change[A]

    object SubSeq {
      def apply(leftIndex: Int, rightIndex: Int, leftLength: Int, rightLength: Int) =
        new SubSeq(Span(leftIndex, leftLength), Span(rightIndex, rightLength))
    }

    case class SubSeq(left: Span, right: Span) {
      def this(leftIndex: Int, rightIndex: Int, commonLength: Int) =
        this(Span(leftIndex, commonLength), Span(rightIndex, commonLength))

      def adjoins(other: SubSeq): Boolean = left.adjoins(other.left) && right.adjoins(other.right)
      def extend(other: SubSeq): SubSeq = SubSeq(left.extend(other.left), right.extend(other.right))

      override def toString: String = s"${left.from}, ${right.from}, ${left.length}, ${right.length}"
    }

    object Span {
      val zero = Span(0, 0)
    }

    case class Span(from: Int, length: Int) {
      def adjoins(other: Span): Boolean = to == other.from
      def extend(other: Span) = Span(from, length + other.length)

      def zip[A](rhs: Iterable[A]): Seq[(Int, A)] = Range(from, to).zip(rhs)
      def slice[A](list: List[A]): List[A] = list.slice(from, to)

      def to: Int = from + length

      def previous(newFrom: Int): Span = Span(newFrom, from - newFrom)
    }

    class ListDelta[A] extends Delta[List[A]] {
      type Out = List[Change[A]]

      def apply(left: List[A], right: List[A]): Out = untypedChanges(left, right).map(_.typed(left, right))

      private def untypedChanges(left: List[A], right: List[A]): List[UntypedChange] = {
        val matches: List[SubSeq] = matchingSequences(left.toVector, right.toVector)

        val (result, _, _) = matches.foldLeft((List.empty[UntypedChange], 0, 0)) {
          case ((acc, leftIndex, rightIndex), head) ⇒
            (equal(head) ?:: insert(head, rightIndex) ?:: remove(head, leftIndex) ?:: acc, head.left.to, head.right.to)
        }

        UntypedChange.merge(result.reverse)
      }

      private def remove(subSeq: SubSeq, lastLeftIndex: Int): Option[UntypedChange] = {
        if (lastLeftIndex == -1) {
          if (subSeq.left.from <= 0 || subSeq.right.from != 0) None else {
            Some(UntypedRemoved(SubSeq(subSeq.left.copy(from = 0), Span.zero)))
          }
        } else {
          if (subSeq.left.from <= lastLeftIndex) None else {
            Some(UntypedRemoved(SubSeq(subSeq.left.previous(lastLeftIndex), Span.zero)))
          }
        }
      }

      def insert(subSeq: SubSeq, lastRightIndex: Int): Option[UntypedChange] = {
        if (lastRightIndex == -1) {
          if (subSeq.right.from <= 0 || subSeq.left.from != 0) None else {
            Some(UntypedInserted(SubSeq(Span.zero, Span(0, subSeq.right.from))))
          }
        } else {
          if (subSeq.right.from <= lastRightIndex) None else {
            Some(UntypedInserted(SubSeq(Span.zero, subSeq.right.previous(lastRightIndex))))
          }
        }
      }

      def equal(subSeq: SubSeq): Option[UntypedChange] =
        if (subSeq.left.length <= 0 || subSeq.left.length != subSeq.right.length) None else Some(UntypedEqual(subSeq))

      // This method uses a lot of random access, so using Vector.
      def matchingSequences(left: Vector[A], right: Vector[A]): List[SubSeq] = {
        val (leftIndexed, rightIndexed) = (left.zipWithIndex, right.zipWithIndex)
        def recurse(lowL: Int, lowR: Int, highL: Int, highR: Int, maxDepth: Int): List[(Int, Int)] = {
          if (maxDepth < 0 || lowL >= highL || lowR >= highR) Nil else {
            val uniqueLCSs = uniqueLongestCommonSubSequences(Slice(leftIndexed, lowL, highL), Slice(rightIndexed, lowR, highR))

            val ((lastPosL, lastPosR), initialResult) = uniqueLCSs.foldLeft(((lowL - 1, lowR - 1), List[(Int, Int)]())) {
              case (((lastLPos, lastRPos), accMatches), (lIndex, rIndex)) ⇒ {
                val (lPos, rPos) = (lIndex + lowL, rIndex + lowR)

                val matches = if (lastLPos + 1 == lPos || lastRPos + 1 == rPos) Nil else {
                  recurse(lastLPos + 1, lPos, lastRPos + 1, rPos, maxDepth - 1)
                }

                ((lPos, rPos), accMatches ++ matches :+ (lPos, rPos))
              }
            }

            val finalResult = if (initialResult.nonEmpty) {
              recurse(lastPosL + 1, lastPosR + 1, highL, highR, maxDepth - 1)
            } else if (left(lowL) == right(lowR)) {
              def matches(lLow: Int, rLow: Int, acc: List[(Int, Int)]): (Int, Int, List[(Int, Int)]) =
                if (lLow >= highL || rLow >= highR || left(lLow) != right(rLow)) (lLow, rLow, acc) else {
                  matches(lLow + 1, rLow + 1, acc :+ (lLow, rLow))
                }

              val (lLow, rLow, start) = matches(lowL, highL, Nil)

              start ++ recurse(lLow, rLow, highL, highR, maxDepth - 1)
            } else if (left(highL - 1) == right(highR - 1)) {
              def matches(lHigh: Int, rHigh: Int, endList: List[(Int, Int)]): (Int, Int, List[(Int, Int)]) =
                if (lHigh <= lowL || rHigh <= lowR || left(lHigh - 1) != right(rHigh - 1)) (lHigh, rHigh, endList) else {
                  matches(lHigh - 1, rHigh - 1, endList :+ (lHigh, rHigh))
                }

              val (lHigh, rHigh, end) = matches(highL - 1, highR - 1, Nil)

              recurse(lastPosL + 1, lastPosR + 1, lHigh, rHigh, maxDepth - 1) ++ end
            } else {
              Nil
            }

            initialResult ++ finalResult
          }
        }

        collapse(recurse(0, 0, left.length, right.length, 10), left.length, right.length)
      }

      private def collapse(list: List[(Int, Int)], leftLength: Int, rightLength: Int): List[SubSeq] = {
        @tailrec def recurse(startL: Int, startR: Int, length: Int, acc: List[SubSeq], current: List[(Int, Int)]): List[SubSeq] = {
          current match {
            case Nil ⇒ if (length == 0) acc else new SubSeq(startL, startR, length) :: acc
            case (l, r) :: tail ⇒ {
              if (startL != -1 && l == (startL + length) && r == (startR + length)) recurse(startL, startR, length + 1, acc, tail) else {
                recurse(l, r, 1, if (startL != -1) new SubSeq(startL, startR, length) :: acc else acc, tail)
              }
            }
          }
        }

        (new SubSeq(leftLength, rightLength, 0) :: recurse(-1, -1, 0, Nil, list)).reverse
      }

      private case class Slice[B](original: Vector[(B, Int)], from: Int, to: Int) {
        def array: Array[Int] = Array.ofDim[Int](to - from)

        def indexedForEach[Discarded](f: ((B, Int)) ⇒ Discarded): Unit = {
          original.slice(from, to).foreach {
            case (b, index) ⇒ f((b, index - from))
          }
        }
      }

      private def uniqueLongestCommonSubSequences(left: Slice[A], right: Slice[A]): List[(Int, Int)] = {
        val index1 = M.Map[A, Int]()
        val index2 = M.Map[A, Int]()
        val ltor = left.array
        val rtol = right.array

        left.indexedForEach {
          case (lhs, index) ⇒ {
            index1 += (if (index1.contains(lhs)) lhs → -1 else lhs → index)
          }
        }

        right.indexedForEach {
          case (rhs, i) ⇒ {
            rtol(i) = -1

            index1.get(rhs).filterNot(_ == -1).foreach(next ⇒ {
              index2.get(rhs).filterNot(_ == -1) match {
                case Some(btoai) ⇒ {
                  rtol(btoai) = -1
                  index1 += (rhs → -1)
                  index2 += (rhs → i)
                }
                case None ⇒ {
                  rtol(i) = next
                  ltor(next) = i
                  index2 += (rhs → i)
                }
              }
            })
          }
        }

        backReferenceLongestCommonSubSequences(rtol.toList.filter(_ != -1)).map(x ⇒ (x, ltor(x))).reverse
      }

      def backReferenceLongestCommonSubSequences(values: List[Int]): List[Int] =
        values.foldLeft(Piles(Nil))(_ add _).follow

      private case class Piles(piles: List[Pile]) {
        def add(value: Int): Piles = {
          @tailrec def recurse(skipped: List[Pile], remaining: List[Pile]): Piles = remaining match {
            case head :: tail if head.top < value ⇒ recurse(head :: skipped, tail)
            case head :: tail ⇒ Piles(
              (head.add(value, skipped.headOption.map(_.top)) :: skipped).reverse ++ tail
            )
            case Nil ⇒ Piles(
              (Pile(value, skipped.headOption.fold(Map.empty[Int, Int])(pile ⇒ Map(value → pile.top))) :: skipped).reverse
            )
          }

          recurse(Nil, piles)
        }

        def follow: List[Int] = {
          @tailrec def recurse(acc: List[Int], next: Option[Int], remaining: List[Pile]): List[Int] = (next, remaining) match {
            case (None, _)                   ⇒ acc.reverse
            case (Some(value), Nil)          ⇒ recurse(value :: acc, None, Nil)
            case (Some(value), head :: tail) ⇒ recurse(value :: acc, head.get(value), tail)
          }

          piles.reverse match {
            case head :: tail ⇒ recurse(List(head.top), head.next, tail)
            case Nil          ⇒ Nil
          }
        }
      }

      private case class Pile(top: Int, values: Map[Int, Int]) {
        def add(value: Int, backReference: Option[Int]): Pile =
          Pile(value, backReference.fold(values)(ref ⇒ values + (value → ref)) )

        def next: Option[Int] = get(top)
        def get(value: Int): Option[Int] = values.get(value)
      }

      object UntypedChange {
        def merge(changes: List[UntypedChange]): List[UntypedChange] = {
          val (remaining, result) = changes.foldLeft((None: Option[UntypedChange], List.empty[UntypedChange])) {
            case ((Some(UntypedRemoved(SubSeq(left, _))), accChanges), UntypedInserted(SubSeq(_, right))) if left.length == right.length ⇒ {
              (None, UntypedReplaced(SubSeq(left, right)) :: accChanges)
            }
            case ((Some(UntypedInserted(SubSeq(_, right))), accInstructions), UntypedRemoved(SubSeq(left, _))) if left.length == right.length ⇒ {
              (None, UntypedReplaced(SubSeq(left, right)) :: accInstructions)
            }
            case ((Some(left: UntypedEqual), accInstructions), right: UntypedEqual) if left.adjoins(right) ⇒ {
              (Some(left.extend(right)), accInstructions)
            }
            case ((Some(prev), accInstructions), instruction) ⇒ (Some(instruction), prev :: accInstructions)
            case ((None, accInstructions), instruction)       ⇒ (Some(instruction), accInstructions)
          }

          remaining.fold(result)(_ :: result).reverse
        }
      }

      sealed trait UntypedChange {
        def typed(left: List[A], right: List[A]): Change[A]
      }

      case class UntypedEqual(subSequence: SubSeq) extends UntypedChange {
        def adjoins(other: UntypedEqual): Boolean = subSequence.adjoins(other.subSequence)
        def extend(other: UntypedEqual): UntypedEqual = UntypedEqual(subSequence.extend(other.subSequence))

        def typed(left: List[A], right: List[A]): Change[A] = Equal(subSequence, subSequence.left.slice(left))
      }

      case class UntypedInserted(subSequence: SubSeq) extends UntypedChange {
        def typed(left: List[A], right: List[A]): Change[A] = Inserted(subSequence, subSequence.right.slice(right))
      }

      case class UntypedReplaced(subSequence: SubSeq) extends UntypedChange {
        def typed(left: List[A], right: List[A]): Change[A] =
          Replaced(subSequence, subSequence.left.slice(left), subSequence.right.slice(right))
      }

      case class UntypedRemoved(subSequence: SubSeq) extends UntypedChange {
        def typed(left: List[A], right: List[A]): Change[A] = Removed(subSequence, subSequence.left.slice(left))
      }

      private implicit class DeltaListOps[B](list: List[B]) {
        def ?::(oa: Option[B]): List[B] = oa.fold(list)(_ :: list)
      }
    }
  }
}
