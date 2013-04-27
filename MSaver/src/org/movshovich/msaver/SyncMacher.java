package org.movshovich.msaver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.MappedByteBuffer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.UpdateBuilder;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.SparseArray;
import android.widget.Toast;

public class SyncMacher extends AsyncTask<Void, Void, Long> {

	public static final String KEY_PREF_SERVER_ADDR = "server_addr";
	public static final String KEY_PREF_USERNAME = "username";
	public static final String KEY_PREF_PASSWORD = "password";
	public static final String KEY_PREF_LAST_PUSH = "last_push";

	private Context context;
	private SharedPreferences pref;

	public SyncMacher(Context context) {
		this.context = context;
		pref = PreferenceManager.getDefaultSharedPreferences(context);
	}

	private boolean doSync() {
		String ipAddr = pref.getString(SyncMacher.KEY_PREF_SERVER_ADDR, null);

		if (isEmpty(ipAddr, "IP address")) {
			return false;
		}
		DefaultHttpClient http = new DefaultHttpClient();
		HttpPost post = new HttpPost(ipAddr);
		post.setHeader("Content-type", "application/json");
		try {
			JSONObject json = getData();
			if (json == null) {
				return false;
			}
			StringEntity se = new StringEntity(json.toString());
			post.setEntity(se);
			HttpResponse resp = http.execute(post);
			processResponse(resp);
		} catch (Exception e) {
			Toast.makeText(context, "Sync failed", Toast.LENGTH_SHORT).show();
			Log.e("MSaver", "Http operations failed", e);
			return false;
		}
		Editor editor = pref.edit();
		editor.putLong(KEY_PREF_LAST_PUSH, System.currentTimeMillis());
		editor.commit();
		return true;
	}

	private boolean isEmpty(String value, String hrName) {
		if (value == null || value.isEmpty()) {
			Toast.makeText(context, "Please fill in " + hrName,
					Toast.LENGTH_SHORT).show();
			return true;
		}
		return false;
	}

	private JSONObject getData() throws JSONException {
		JSONObject json = new JSONObject();
		String username = pref.getString(KEY_PREF_USERNAME, null);
		String password = pref.getString(KEY_PREF_PASSWORD, null);
		if (isEmpty(username, "username") || isEmpty(password, "password")) {
			return null;
		}
		long lastPushTs = pref.getLong(KEY_PREF_LAST_PUSH, 0);
		Date lastPush = new Date(lastPushTs);
		json.put("username", username);
		json.put("password", password);

		Collection<Transaction> txs;
		SparseArray<Product> prods;
		SparseArray<Category> cats;
		try {
			txs = getTransaction(lastPush);
			prods = getProducts(txs);
			cats = getCategories(prods);
		} catch (SQLException e) {
			Toast.makeText(context, "Sync failed", Toast.LENGTH_SHORT).show();
			Log.e("MSaver", "Database queries failed", e);
			return null;
		}
		json.put("transactions", transactions2JsonArray(txs));
		json.put("products", products2JsonArray(prods));
		json.put("categories", categories2JsonArray(cats));
		Log.w("MSaver", json.toString(3));
		return json;
	}

	private JSONArray categories2JsonArray(SparseArray<Category> cats)
			throws JSONException {
		JSONArray jsonProds = new JSONArray();
		int size = cats.size();
		for (int i = 0; i < size; i += 1) {
			jsonProds.put(toJson(cats.valueAt(i)));
		}
		return jsonProds;
	}

	private JSONArray products2JsonArray(SparseArray<Product> prods)
			throws JSONException {
		JSONArray jsonProds = new JSONArray();
		int size = prods.size();
		for (int i = 0; i < size; i += 1) {
			jsonProds.put(toJson(prods.valueAt(i)));
		}
		return jsonProds;
	}

	private JSONArray transactions2JsonArray(Collection<Transaction> txs)
			throws JSONException {
		JSONArray jsonTxs = new JSONArray();
		for (Transaction t : txs) {
			jsonTxs.put(toJson(t));
		}
		return jsonTxs;
	}

	private JSONObject toJson(Transaction t) throws JSONException {
		JSONObject res = new JSONObject();
		res.put("id", t.getId());
		res.put("price", t.getPrice());
		Calendar cal = Calendar.getInstance();
		cal.setTime(t.getDate());
		res.put("date", cal.getTimeInMillis());
		res.put("prodId", t.getProduct().getId());
		return res;
	}

	private JSONObject toJson(Product p) throws JSONException {
		JSONObject res = new JSONObject();
		res.put("id", p.getId());
		res.put("name", p.getName());
		res.put("categoryId", p.getCategory().getId());
		return res;
	}

	private JSONObject toJson(Category c) throws JSONException {
		JSONObject res = new JSONObject();
		res.put("id", c.getId());
		res.put("name", c.getName());
		return res;
	}

	private SparseArray<Category> getCategories(SparseArray<Product> prods)
			throws SQLException {
		SparseArray<Category> res = new SparseArray<Category>();
		Dao<Category, Integer> catDao = MainActivity.databaseHelper
				.getCategoryDao();
		int size = prods.size();
		for (int i = 0; i < size; i += 1) {
			Category cat = prods.valueAt(i).getCategory();
			if (res.get(cat.getId()) == null) {
				catDao.refresh(cat);
				res.put(cat.getId(), cat);
			}
		}
		return res;
	}

	private SparseArray<Product> getProducts(Collection<Transaction> txs)
			throws SQLException {
		SparseArray<Product> res = new SparseArray<Product>();
		Dao<Product, Integer> prodDao = MainActivity.databaseHelper
				.getProductDao();
		for (Transaction tx : txs) {
			Product product = tx.getProduct();
			if (res.get(product.getId()) == null) {
				prodDao.refresh(product);
				res.put(product.getId(), product);
			}
		}
		return res;
	}

	private List<Transaction> getTransaction(Date lastPush) throws SQLException {
		Dao<Transaction, Integer> txDao = MainActivity.databaseHelper
				.getTransactionDao();
		return txDao.queryBuilder().where().ge("date", lastPush).query();
	}

	private void processResponse(HttpResponse resp) throws IOException,
			JSONException, SQLException {
		InputStream content = resp.getEntity().getContent();
		BufferedReader reader = new BufferedReader(new InputStreamReader(
				content, "UTF-8"));
		StringBuilder sb = new StringBuilder(128);
		String line = reader.readLine();
		while (line != null) {
			sb.append(line);
			line = reader.readLine();
		}
		JSONObject json = new JSONObject(sb.toString());
		processResponse(json);
	}

	private void processResponse(JSONObject json) throws JSONException,
			SQLException {
		String errorStr = json.getString("error");
		if (errorStr != null) {
			Toast.makeText(context, "Sync failed: " + errorStr, Toast.LENGTH_SHORT).show();
			return;
		}
		JSONArray prods = json.getJSONArray("products");
		JSONArray cats = json.getJSONArray("categories");
		JSONArray shopList = json.getJSONArray("shoppingList");
		SparseArray<Category> newCats = processCategories(cats);
		SparseArray<Product> newProds = processProducts(prods, newCats);
		showShoppingList(shopList, newProds);
	}

	private void showShoppingList(JSONArray shopList, SparseArray<Product> newProds) throws JSONException, SQLException {
		Dao<Product, Integer> prodDao = MainActivity.databaseHelper
				.getProductDao();
		int length = shopList.length();
		List<Product> list = new ArrayList<Product>(length);
		for (int i = 0; i < length; i += 1) {
			int itemId = shopList.getInt(i);
			Product p;
			if (itemId > 0) {
				p = prodDao.queryForId(itemId);
			} else {
				p = newProds.get(itemId);
			}
			if (p == null) {
				Log.w("MSaver", "Recieved shopping list with unknown porduct id " + itemId);
			} else {
				list.add(p);
			}
		}
		
	}

	private SparseArray<Category> processCategories(JSONArray cats)
			throws JSONException, SQLException {
		int len = cats.length();
		Dao<Category, Integer> catDao = MainActivity.databaseHelper
				.getCategoryDao();
		SparseArray<Category> newCats = new SparseArray<Category>();
		for (int i = 0; i < len; i += 1) {
			JSONObject obj = cats.getJSONObject(i);
			int id = obj.getInt("id");
			String nameVal = obj.getString("name");
			if (id > 0) { // that's a name change for existing category
				if (nameVal != null && !nameVal.isEmpty()) {
					UpdateBuilder<Category, Integer> ub = catDao
							.updateBuilder();
					ub.where().idEq(id);
					ub.updateColumnValue("name", nameVal);
					ub.update();
				}
			} else { // create new categories
				Category cat = new Category();
				cat.setName(nameVal);
				catDao.create(cat);
				newCats.put(id, cat);
			}
		}
		return newCats;
	}

	private SparseArray<Product> processProducts(JSONArray prods, SparseArray<Category> newCats)
			throws JSONException, SQLException {
		int length = prods.length();
		Dao<Product, Integer> prodDao = MainActivity.databaseHelper
				.getProductDao();
		SparseArray<Product> newProds = new SparseArray<Product>();
		for (int i = 0; i < length; i += 1) {
			JSONObject obj = prods.getJSONObject(i);
			int id = obj.getInt("id");
			String nameVal = obj.getString("name");
			if (id > 0) { // that's a name change for existing product
				if (nameVal != null && !nameVal.isEmpty()) {
					UpdateBuilder<Product, Integer> ub = prodDao
							.updateBuilder();
					ub.where().idEq(id);
					ub.updateColumnValue("name", nameVal);
					ub.update();
				}
			} else { // create new product
				Product prod = new Product();
				prod.setName(nameVal);
				int catId = obj.getInt("catId");
				Category cat;
				if (catId > 0) {
					cat = new Category();
					cat.setId(catId);
				} else {
					cat = newCats.get(catId);
				}
				prod.setCategory(cat);
				prodDao.create(prod);
				newProds.put(id, prod);
			}
		}
		return newProds;
	}

	@Override
	protected Long doInBackground(Void... params) {
		boolean success = doSync();
		return success ? 1L : 0L;
	}

	@Override
	protected void onPostExecute(Long result) {
		super.onPostExecute(result);
		Toast.makeText(context, "Sync success!", Toast.LENGTH_SHORT).show();
	}
}