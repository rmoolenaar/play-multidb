package play.db;

import java.sql.Connection;
import java.sql.SQLException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import play.db.jpa.MJPAPlugin;
import play.mvc.Http.Request;

public class RoutingDataSource extends AbstractRoutingDataSource {

	public static Log log = LogFactory.getLog(RoutingDataSource.class);

	@Override
    protected Object determineCurrentLookupKey() {
		log.debug("Extracting routing DataSource key from request: " + Request.current());

		DbParameters dbParm = getDbParameter();
		return dbParm.url;
    }

	@Override
	public Connection getConnection() throws SQLException {
		DbParameters dbParm = getDbParameter();
		return getConnection(dbParm.user, dbParm.pass);
	}

	DbParameters getDbParameter() {
		DbParameters dbParm = MDB.credentials.get(MJPAPlugin.keyExtractor.extractKey(Request.current()));
		if (dbParm == null) {
			return (DbParameters)MDB.credentials.values().toArray()[0];
		}
		return dbParm;
	}
}
