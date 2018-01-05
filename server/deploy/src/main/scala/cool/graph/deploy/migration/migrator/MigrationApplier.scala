package cool.graph.deploy.migration.migrator

import cool.graph.deploy.database.persistence.MigrationPersistence
import cool.graph.deploy.migration.MigrationStepMapper
import cool.graph.deploy.migration.mutactions.ClientSqlMutaction
import cool.graph.shared.models.{Migration, MigrationStatus, MigrationStep, Schema}
import cool.graph.utils.exceptions.StackTraceUtils
import slick.jdbc.MySQLProfile.backend.DatabaseDef

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait MigrationApplier {
  def apply(previousSchema: Schema, migration: Migration): Future[MigrationApplierResult]
}
case class MigrationApplierResult(succeeded: Boolean)

case class MigrationApplierImpl(
    migrationPersistence: MigrationPersistence,
    clientDatabase: DatabaseDef,
    migrationStepMapper: MigrationStepMapper
)(implicit ec: ExecutionContext)
    extends MigrationApplier {

  override def apply(previousSchema: Schema, migration: Migration): Future[MigrationApplierResult] = {
    for {
      _         <- Future.unit
      nextState = if (migration.status == MigrationStatus.Pending) MigrationStatus.InProgress else migration.status
      _         <- migrationPersistence.updateMigrationStatus(migration.id, nextState)
      result    <- recurse(previousSchema, migration)
    } yield result
  }

  def recurse(previousSchema: Schema, migration: Migration): Future[MigrationApplierResult] = {
    if (!migration.isRollingBack) {
      recurseForward(previousSchema, migration)
    } else {
      recurseForRollback(previousSchema, migration)
    }
  }

  def recurseForward(previousSchema: Schema, migration: Migration): Future[MigrationApplierResult] = {
    if (migration.pendingSteps.nonEmpty) {
      val result = for {
        _             <- applyStep(previousSchema, migration, migration.currentStep)
        nextMigration = migration.incApplied
        _             <- migrationPersistence.updateMigrationApplied(migration.id, nextMigration.applied)
        x             <- recurseForward(previousSchema, nextMigration)
      } yield x

      result.recoverWith {
        case exception =>
          println("encountered exception while applying migration. will roll back.")
          for {
            _             <- migrationPersistence.updateMigrationStatus(migration.id, MigrationStatus.RollingBack)
            _             <- migrationPersistence.updateMigrationErrors(migration.id, migration.errors :+ StackTraceUtils.print(exception))
            applierResult <- recurseForRollback(previousSchema, migration.copy(status = MigrationStatus.RollingBack))
          } yield applierResult
      }
    } else {
      migrationPersistence.updateMigrationStatus(migration.id, MigrationStatus.Success).map(_ => MigrationApplierResult(succeeded = true))
    }
  }

  def recurseForRollback(previousSchema: Schema, migration: Migration): Future[MigrationApplierResult] = {
    def continueRollback = {
      val nextMigration = migration.incRolledBack
      for {
        _ <- migrationPersistence.updateMigrationRolledBack(migration.id, nextMigration.rolledBack)
        x <- recurseForRollback(previousSchema, nextMigration)
      } yield x
    }
    def abortRollback(err: Throwable) = {
      println("encountered exception while unapplying migration. will abort.")
      val failedMigration = migration.markAsRollBackFailure
      for {
        _ <- migrationPersistence.updateMigrationStatus(migration.id, failedMigration.status)
        _ <- migrationPersistence.updateMigrationErrors(migration.id, failedMigration.errors :+ StackTraceUtils.print(err))
      } yield MigrationApplierResult(succeeded = false)
    }

    if (migration.pendingRollBackSteps.nonEmpty) {
      unapplyStep(previousSchema, migration, migration.pendingRollBackSteps.head).transformWith {
        case Success(_)   => continueRollback
        case Failure(err) => abortRollback(err)
      }
    } else {
      migrationPersistence.updateMigrationStatus(migration.id, MigrationStatus.RollbackSuccess).map(_ => MigrationApplierResult(succeeded = false))
    }
  }

  def applyStep(previousSchema: Schema, migration: Migration, step: MigrationStep): Future[Unit] = {
    migrationStepMapper.mutactionFor(previousSchema, migration.schema, step) match {
      case Some(mutaction) => executeClientMutaction(mutaction)
      case None            => Future.unit
    }
  }

  def unapplyStep(previousSchema: Schema, migration: Migration, step: MigrationStep): Future[Unit] = {
    migrationStepMapper.mutactionFor(previousSchema, migration.schema, step) match {
      case Some(mutaction) => executeClientMutactionRollback(mutaction)
      case None            => Future.unit
    }
  }

  def executeClientMutaction(mutaction: ClientSqlMutaction): Future[Unit] = {
    for {
      statements <- mutaction.execute
      _          <- clientDatabase.run(statements.sqlAction)
    } yield ()
  }

  def executeClientMutactionRollback(mutaction: ClientSqlMutaction): Future[Unit] = {
    for {
      statements <- mutaction.rollback.get
      _          <- clientDatabase.run(statements.sqlAction)
    } yield ()
  }
}
