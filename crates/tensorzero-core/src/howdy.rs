//! Deployment ID management.
//!
//! The deployment ID is a 64-char hex hash used to identify a specific
//! TensorZero deployment. It is needed by the Autopilot subsystem.
//!
//! The original Howdy telemetry (which sent usage data to howdy.tensorzero.com)
//! has been removed.

use tracing::Level;

use crate::db::DeploymentIdQueries;
use crate::db::clickhouse::ClickHouseConnectionInfo;
use crate::db::clickhouse::clickhouse_client::ClickHouseClientType;
use crate::db::delegating_connection::{DelegatingDatabaseConnection, PrimaryDatastore};
use crate::db::postgres::PostgresConnectionInfo;

/// Synchronizes the deployment ID from ClickHouse to Postgres if Postgres is enabled.
/// For existing ClickHouse deployments, we make sure Postgres contains the same deployment ID.
/// This only executes the actual synchronization once.
async fn synchronize_deployment_id(
    clickhouse: &ClickHouseConnectionInfo,
    postgres: &PostgresConnectionInfo,
    primary_datastore: PrimaryDatastore,
) -> Result<(), ()> {
    if primary_datastore != PrimaryDatastore::Postgres {
        return Ok(());
    }
    if clickhouse.client_type() != ClickHouseClientType::Production {
        return Ok(());
    }
    if !matches!(postgres, &PostgresConnectionInfo::Enabled { .. }) {
        return Ok(());
    }
    let Ok(id) = clickhouse.get_deployment_id().await else {
        tracing::debug!("Failed to get deployment ID from ClickHouse");
        return Err(());
    };
    if let Err(e) = postgres.insert_deployment_id(&id).await {
        tracing::debug!("Failed to sync deployment ID to Postgres: {e:?}");
        return Err(());
    }

    Ok(())
}

/// Gets the deployment ID.
/// This is a 64 char hex hash that is used to identify the deployment.
pub async fn get_deployment_id(
    clickhouse: &ClickHouseConnectionInfo,
    postgres: &PostgresConnectionInfo,
    primary_datastore: PrimaryDatastore,
) -> Result<String, ()> {
    synchronize_deployment_id(clickhouse, postgres, primary_datastore).await?;

    DelegatingDatabaseConnection::new(clickhouse.clone(), postgres.clone(), primary_datastore)
        .get_deployment_id()
        .await
        .map_err(|e| {
            e.log_at_level("Failed to get deployment ID: ", Level::DEBUG);
        })
}
