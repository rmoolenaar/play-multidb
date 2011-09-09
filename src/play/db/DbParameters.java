package play.db;

import org.apache.commons.lang.StringUtils;

public class DbParameters {
	public String key;
	public String url;
	public String driver;
	public String user;
	public String pass;
	public String sql;
	public String urlPrefix;
	public String poolTimeout;
	public String idleTime;
	public String poolMaxSize;
	public String poolMinSize;
	public void inherit(DbParameters allEntry)
	{
		this.driver = StringUtils.defaultIfEmpty(this.driver, allEntry.driver);
		this.pass = StringUtils.defaultIfEmpty(this.pass, allEntry.pass);
		this.user = StringUtils.defaultIfEmpty(this.user, allEntry.user);
		this.poolMaxSize = StringUtils.defaultIfEmpty(this.poolMaxSize, allEntry.poolMaxSize);
		this.poolMinSize = StringUtils.defaultIfEmpty(this.poolMinSize, allEntry.poolMinSize);
		this.poolTimeout = StringUtils.defaultIfEmpty(this.poolTimeout, allEntry.poolTimeout);
		this.idleTime = StringUtils.defaultIfEmpty(this.idleTime, allEntry.idleTime);
		this.urlPrefix = StringUtils.defaultIfEmpty(this.urlPrefix, allEntry.urlPrefix);
		this.sql = StringUtils.defaultIfEmpty(this.sql, allEntry.sql);
		this.url = StringUtils.defaultIfEmpty(this.url, allEntry.url);
	}
}