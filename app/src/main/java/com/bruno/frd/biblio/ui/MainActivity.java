package com.bruno.frd.biblio.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bruno.frd.biblio.R;
import com.bruno.frd.biblio.data.api.BiblioApi;
import com.bruno.frd.biblio.data.api.model.ApiError;
import com.bruno.frd.biblio.data.api.model.ApiMessageResponse;
import com.bruno.frd.biblio.data.api.model.ApiResponsePrestamos;
import com.bruno.frd.biblio.data.api.model.PrestamosDisplayList;
import com.bruno.frd.biblio.data.api.model.RegIDTokenBody;
import com.bruno.frd.biblio.data.prefs.SessionPrefs;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import com.danimahardhika.cafebar.CafeBar;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = MainActivity.class.getSimpleName();

    private static final int STATUS_FILTER_DEFAULT_VALUE = 0;

    private Retrofit mRestAdapter;
    private BiblioApi mBiblioApi;

    private RecyclerView mLoansList;
    private PrestamosAdapter mLoansAdapter;
    private View mEmptyStateContainer;
    private Spinner mStatusFilterSpinner;
    private FloatingActionButton mFab;
    private BroadcastReceiver messageReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Redirección al Login si no está logueado
        if (!SessionPrefs.get(this).isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // Remover título de la action bar
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        toolbar.setOverflowIcon(ContextCompat.getDrawable(getApplicationContext(),R.drawable.ic_baseline_person_24));

        //Botón de búsqueda
        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showSearchScreen();
            }
        });

        // Configuramos el filtro para los préstamos (todos, vencidos, etc)
        mStatusFilterSpinner = (Spinner) findViewById(R.id.toolbar_spinner);
        mStatusFilterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Ejecutar filtro de citas médicas
                String status = parent.getItemAtPosition(position).toString();
                loadLoans(status);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        ArrayAdapter<String> statusFilterAdapter =
                new ArrayAdapter<>(
                        toolbar.getContext(),
                        android.R.layout.simple_spinner_item,
                        PrestamosDisplayList.STATES_VALUES);
        mStatusFilterSpinner.setAdapter(statusFilterAdapter);
        statusFilterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mLoansList = (RecyclerView) findViewById(R.id.list_loans);
        mLoansAdapter = new PrestamosAdapter(this, new ArrayList<PrestamosDisplayList>(0));
        mLoansAdapter.setOnItemClickListener(new PrestamosAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(PrestamosDisplayList clickedItem) {
                Intent item_intent = new Intent(MainActivity.this, ItemActivity.class);
                Bundle bundle = new Bundle();
                bundle.putSerializable("DATA", clickedItem);
                item_intent.putExtras(bundle);
                startActivity(item_intent);
            }

            @Override
            public void onRenewBook(PrestamosDisplayList copy) {
                renewBook(copy.getBibid(), copy.getCopyid());
            }

        });

        mLoansList.setAdapter(mLoansAdapter);
        mEmptyStateContainer = findViewById(R.id.empty_state_container);

        // Configuramos el swipe para recargar
        SwipeRefreshLayout swipeRefreshLayout =
                (SwipeRefreshLayout) findViewById(R.id.refresh_layout);
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                // Pedir al servidor información reciente
                mStatusFilterSpinner.setSelection(STATUS_FILTER_DEFAULT_VALUE);
                loadLoans(getCurrentState());
            }
        });

        // Creamos el adaptador de Retrofit
        Gson gson = new GsonBuilder()
                .setDateFormat("yyyy-MM-dd")
                .create();
        mRestAdapter = new Retrofit.Builder()
                .baseUrl(BiblioApi.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        // Creamos conexión a la API de la app
        mBiblioApi = mRestAdapter.create(BiblioApi.class);

        // Reviso si puede recibir notificaciones
        if (checkGooglePlayServices()) {
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(new OnCompleteListener<String>() {
                        @Override
                        public void onComplete(@NonNull Task<String> task) {
                            if (!task.isSuccessful()) {
                                Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                                return;
                            }
                            // Get new FCM registration token
                            // Obtener token de Firebase para mandar notificaciones
                            String token = task.getResult();
                            // Log.d(TAG, "Firebase Token: " + token);
                            sendRegistrationToServer(token);
                        }
                    });
        } else {
            Log.w(TAG, "Device doesn't have Google Play Services");
        }

        // Configuramos el broadcast que va a recibir las notificaciones enviadas desde el servicio MyFirebaseMessagingService mientras la app esta abierta.
        // Los mensajes son recibidos por una instacia de MyFirebaseMessagingService, pero para mostrar un cartel o snackbar con el mensaje hay que hacer un broadcast a ActivityMain ya
        // que no es posible controlar la ui (carteles, etc) desde una clase que es un servicio.
        messageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle extras = intent.getExtras();
                if (extras != null) {
                    String text = extras.getString("message");
                    CoordinatorLayout coordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinator);
                    CafeBar.builder(coordinatorLayout.getContext())
                            .floating(true)
                            .content(text)
                            .to(coordinatorLayout)
                            .neutralText("Aceptar")
                            .duration(CafeBar.Duration.LONG)
                            .show();
                    loadLoans(getCurrentState());
                }
            }
        };
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_loans, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            // Antes de desloguear vamos a ver si hay internet para que el servidor se entere, sino vamos a seguir enviandole notificaciones
            logOut();
        }

        if (id == R.id.profile) {
            startActivity(new Intent(this, ProfileActivity.class));
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, new IntentFilter("MyData"));
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver);
    }


    @Override
    protected void onResume() {
        super.onResume();
        // load loans
        //loadLoans(getCurrentState());
    }

    private String getCurrentState() {
        //Spinner mStatusFilterSpinner = (Spinner) findViewById(R.id.toolbar_spinner);
        String status = (String) mStatusFilterSpinner.getSelectedItem();
        return status;
    }

    public void loadLoans(String rawStatus) {
        showLoadingIndicator(true);
        String token = SessionPrefs.get(this).getToken();
        String id = SessionPrefs.get(this).getID();
        String status = "";

        // Elegir valor del estado según la opción del spinner
        switch (rawStatus) {
            case "Renovable":
                status = "renewable";
                break;
            case "Vencido":
                status = "overdue";
                break;
            case "No renovable":
                status = "nonrenewable";
                break;
            default:
                status = "Todos";
        }

        // Construir mapa de parámetros
        HashMap<String, Object> parameters = new HashMap<>();
        parameters.put("display", "list");
        parameters.put("status", status);

        // Realizar petición HTTP
        Call<ApiResponsePrestamos> call = mBiblioApi.getPrestamos(token, id, parameters);
        call.enqueue(new Callback<ApiResponsePrestamos>() {
            @Override
            public void onResponse(Call<ApiResponsePrestamos> call,
                                   Response<ApiResponsePrestamos> response) {
                try {
                    if (!response.isSuccessful()) {
                        // Procesar error de API
                        String error = "Ha ocurrido un error. Contacte al administrador.";
                        String deverror = null;
                        if (response.errorBody()
                                .contentType()
                                .subtype()
                                .equals("json")) {
                            ApiError apiError = ApiError.fromResponseBody(response.errorBody());

                            error = apiError.getMessage();
                            deverror = apiError.getDeveloperMessage();
                            Log.d(TAG, apiError.getDeveloperMessage());
                        } else {
                            // Reportar causas de error no relacionado con la API
                            try {
                                Log.d(TAG, response.errorBody().string());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        showLoadingIndicator(false);
                        showErrorMessage(error);
                        if (Objects.equals(deverror, "wrongtoken")) {
                            logOut();
                        }
                        return;
                    }
                    List<PrestamosDisplayList> serverLoans = response.body().getResults();
                    Log.d(TAG, response.body().getResults().toString());

                    if (serverLoans.size() > 0) {
                        // Mostrar lista de préstamos
                        showLoans(serverLoans);
                    } else {
                        // Mostrar empty state
                        showNoLoans();
                    }
                    showLoadingIndicator(false);
                }
                catch(Exception e) {
                    // Reportar el status code en caso de que el error no tenga body
                    Log.d(TAG, String.valueOf(response.code()));
                    showNoLoans();
                    showLoadingIndicator(false);
                    showErrorMessage("HTTP Error: " + String.valueOf(response.code()));
                }
            }

            @Override
            public void onFailure(Call<ApiResponsePrestamos> call, Throwable t) {
                showLoadingIndicator(false);
                Log.d(TAG, "Petición rechazada:" + t.getMessage());
                showErrorMessage("No hay conexión con el servidor");
            }
        });
    }

    private void showLoadingIndicator(final boolean show) {
        final SwipeRefreshLayout refreshLayout =
                (SwipeRefreshLayout) findViewById(R.id.refresh_layout);
        refreshLayout.post(new Runnable() {
            @Override
            public void run() {
                refreshLayout.setRefreshing(show);
            }
        });
    }

    private void showErrorMessage(String error) {
        CoordinatorLayout coordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinator);
        CafeBar.builder(coordinatorLayout.getContext())
                .floating(true)
                .content(error)
                .to(coordinatorLayout)
                .neutralText("Aceptar")
                .duration(CafeBar.Duration.LONG)
                .show();
        //Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        //loadLoans(getCurrentState());
    }

    private void showLoans(List<PrestamosDisplayList> serverLoans) {
        mLoansAdapter.swapItems(serverLoans);

        mLoansList.setVisibility(View.VISIBLE);
        mEmptyStateContainer.setVisibility(View.GONE);

    }

    private void showNoLoans() {
        mLoansList.setVisibility(View.GONE);
        mEmptyStateContainer.setVisibility(View.VISIBLE);
    }

    private void renewBook(int bibId, int copyId) {

        // Obtener token de usuario
        String token = SessionPrefs.get(this).getToken();
        String id = SessionPrefs.get(this).getID();

        // Preparar cuerpo de la petición
        HashMap<String, String> statusMap = new HashMap<>();
        statusMap.put("status", "Cancelada");

        // Enviar petición
        mBiblioApi.renewBook(bibId, copyId, token, id).enqueue(
                new Callback<ApiMessageResponse>() {
                    @Override
                    public void onResponse(Call<ApiMessageResponse> call,
                                           Response<ApiMessageResponse> response) {
                        if (!response.isSuccessful()) {
                            // Procesar error de API
                            String error = "Ha ocurrido un error. Contacte al administrador.";
                            String deverror = null;
                            if (response.errorBody()
                                    .contentType()
                                    .subtype()
                                    .equals("json")) {
                                ApiError apiError = ApiError.fromResponseBody(response.errorBody());

                                error = apiError.getMessage();
                                deverror = apiError.getDeveloperMessage();
                                Log.d(TAG, apiError.getDeveloperMessage());
                            } else {
                                try {
                                    // Reportar causas de error no relacionado con la API
                                    Log.d(TAG, response.errorBody().string());
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                            if (Objects.equals(deverror, "wrongtoken")) {
                                logOut();
                            }
                            showErrorMessage(error);
                            return;
                        }

                        // Cancelación Exitosa
                        Log.d(TAG, response.body().getResults().getMessage());
                        String message = response.body().getResults().getMessage();
                        //Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        CoordinatorLayout coordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinator);

                        CafeBar.builder(coordinatorLayout.getContext())
                                .floating(true)
                                .content(message)
                                .to(coordinatorLayout)
                                .neutralText("Aceptar")
                                .duration(CafeBar.Duration.LONG)
                                .show();
                        loadLoans(getCurrentState());
                    }

                    @Override
                    public void onFailure(Call<ApiMessageResponse> call, Throwable t) {
                        Log.d(TAG, "Petición rechazada:" + t.getMessage());
                        showErrorMessage("Error de comunicación");
                    }
                }
        );
    }

    private void showSearchScreen() {
        startActivity(new Intent(MainActivity.this,SearchActivity.class));
        //finish();
    }

    private boolean checkGooglePlayServices() {
        // 1
        int status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        // 2
        if (status != ConnectionResult.SUCCESS) {
            Log.e(TAG, "Error");
            // ask user to update google play services and manage the error.
            return false;
        } else {
            // 3
            Log.i(TAG, "Google play services updated");
            return true;
        }
    }

    private void sendRegistrationToServer(String regidtoken) {

        // Creamos el adaptador de Retrofit
        Gson gson = new GsonBuilder()
                .setDateFormat("yyyy-MM-dd")
                .create();
        mRestAdapter = new Retrofit.Builder()
                .baseUrl(BiblioApi.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        String usertoken = SessionPrefs.get(this).getToken();
        String id = SessionPrefs.get(this).getID();

        // Creamos conexión a la API de la app
        mBiblioApi = mRestAdapter.create(BiblioApi.class);
        Call<ApiMessageResponse> sendToken = mBiblioApi.sendToken(usertoken, id, new RegIDTokenBody(regidtoken));
        sendToken.enqueue(new Callback<ApiMessageResponse>() {
            @Override
            public void onResponse(Call<ApiMessageResponse> call, Response<ApiMessageResponse> response) {
                try {
                    if (!response.isSuccessful()) {
                        String error = "Ha ocurrido un error. Contacte al administrador.";
                        String deverror = null;
                        if (response.errorBody()
                                .contentType()
                                .subtype()
                                .equals("json")) {
                            ApiError apiError = ApiError.fromResponseBody(response.errorBody());

                            error = apiError.getMessage();
                            deverror = apiError.getDeveloperMessage();
                            Log.d(TAG, apiError.getDeveloperMessage());
                        } else {
                            try {
                                // Reportar causas de error no relacionado con la API
                                Log.d(TAG, response.errorBody().string());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        return;
                    }
                } catch(Exception e) {
                    // Reportar el status code en caso de que el error no tenga body
                    Log.d(TAG, String.valueOf(response.code()));
                }
                // Procesar errores
            }

            @Override
            public void onFailure(Call<ApiMessageResponse> call, Throwable t) {
                Log.d(TAG, "Petición rechazada:" + t.getMessage());
            }
        });

    }

    private boolean internetConnectionAvailable(int timeOut) {
        InetAddress inetAddress = null;
        try {
            Future<InetAddress> future = Executors.newSingleThreadExecutor().submit(new Callable<InetAddress>() {
                @Override
                public InetAddress call() {
                    try {
                        return InetAddress.getByName("asus.lan");
                    } catch (UnknownHostException e) {
                        return null;
                    }
                }
            });
            inetAddress = future.get(timeOut, TimeUnit.MILLISECONDS);
            future.cancel(true);
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        } catch (TimeoutException e) {
        }
        return inetAddress!=null && !inetAddress.equals("");
    }

    private void logOut() {
        // Al desloguarse mandamos un FCM Registration ID nulo al server para que no le sigan llegando notificaciones
        if (internetConnectionAvailable(500)) {
            sendRegistrationToServer(null);
            SessionPrefs.get(this).logOut();
            startActivity(new Intent(this, LoginActivity.class));
        } else {
            showErrorMessage("No hay conexión con el servidor");
            loadLoans(getCurrentState());
        }
    }
}
