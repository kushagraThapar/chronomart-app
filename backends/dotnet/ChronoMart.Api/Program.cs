using System.Text.Json;
using Microsoft.Azure.Cosmos;

namespace ChronoMart.Api;

public partial class Program
{
    public static void Main(string[] args)
    {
        WebApplicationBuilder builder = WebApplication.CreateBuilder(args);
        builder.WebHost.UseUrls(builder.Configuration["ASPNETCORE_URLS"] ?? "http://0.0.0.0:8102");
        builder.Services.ConfigureHttpJsonOptions(options =>
        {
            options.SerializerOptions.PropertyNamingPolicy = JsonDefaults.Options.PropertyNamingPolicy;
            options.SerializerOptions.DefaultIgnoreCondition = JsonDefaults.Options.DefaultIgnoreCondition;
            options.SerializerOptions.PropertyNameCaseInsensitive = true;
        });
        builder.Services.AddHealthChecks();

        builder.Services.AddSingleton(serviceProvider =>
            ChronoMartOptions.FromConfiguration(serviceProvider.GetRequiredService<IConfiguration>()));
        builder.Services.AddSingleton(serviceProvider =>
            CosmosClientFactory.Create(serviceProvider.GetRequiredService<ChronoMartOptions>()));
        builder.Services.AddSingleton<DiagnosticsRecorder>();
        builder.Services.AddSingleton<VectorCapabilityState>();
        builder.Services.AddScoped<RequestCosmosMetrics>();
        builder.Services.AddScoped<CosmosDataService>();
        builder.Services.AddHostedService<CosmosProvisioner>();

        WebApplication app = builder.Build();
        app.UseMiddleware<ErrorHandlingMiddleware>();
        app.UseMiddleware<StandardHeadersMiddleware>();
        MapEndpoints(app);
        app.Run();
    }

    internal static void MapEndpoints(WebApplication app)
    {
        app.MapGet("/healthz", () => Results.Ok(new { status = "ok" }));
        app.MapGet("/api/v1/healthz", () => Results.Ok(new { status = "ok" }));

        RouteGroupBuilder api = app.MapGroup("/api/v1");
        MapMeta(api);
        MapSellers(api);
        MapProducts(api);
        MapCustomers(api);
        MapOrders(api);
        MapReviews(api);
        MapCart(api);
        MapInventory(api);
        MapCrossCutting(api);
        MapUnsupportedWorkloads(api);
    }

    private static void MapMeta(RouteGroupBuilder api)
    {
        RouteGroupBuilder meta = api.MapGroup("/_meta");
        meta.MapGet("/capabilities", (VectorCapabilityState state) =>
            Results.Ok(CapabilityProvider.Create(state)));
        meta.MapGet("/diagnostics", (
            int? last,
            DiagnosticsRecorder recorder) =>
        {
            int count = ContractValidation.Bounded(last, 50, 1, 1000, "last");
            return Results.Ok(recorder.Last(count));
        });
        meta.MapGet("/feed-ranges", async (
            string? container,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            ContractValidation.AllowedContainer(container);
            return Results.Ok(await cosmos.FeedRangesAsync(container!, cancellationToken));
        });
        meta.MapGet("/caches", async (
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
            Results.Ok(await cosmos.CacheSnapshotAsync(cancellationToken)));
    }

    private static void MapSellers(RouteGroupBuilder api)
    {
        RouteGroupBuilder sellers = api.MapGroup("/sellers");
        sellers.MapGet("/", async (
            int? limit,
            int? pageSize,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            int size = ResolveSellerLimit(limit, pageSize);
            PageResponse<Seller> page = await cosmos.QueryPageAsync<Seller>(
                ContainerNames.Sellers,
                new QueryDefinition("SELECT TOP @limit * FROM c ORDER BY c.id")
                    .WithParameter("@limit", size),
                null,
                size,
                null,
                -1,
                cancellationToken);
            return Results.Ok(page.Items);
        });
        sellers.MapPost("/", async (
            Seller seller,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            ValidateSeller(seller);
            Seller created = await cosmos.CreateAsync(ContainerNames.Sellers, seller, cancellationToken);
            return Results.Json(created, JsonDefaults.Options, statusCode: StatusCodes.Status201Created);
        });
        sellers.MapGet("/{id}", async (
            string id,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            ContractValidation.Required(id, "id");
            Seller? seller = await cosmos.ReadAsync<Seller>(
                ContainerNames.Sellers, id, new PartitionKey(id), cancellationToken);
            return seller is null
                ? throw ContractValidation.NotFound("Seller", id)
                : Results.Ok(seller);
        });
        sellers.MapPut("/{id}", async (
            string id,
            Seller seller,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            Seller canonical = seller with { Id = ContractValidation.Required(id, "id") };
            ValidateSeller(canonical);
            return Results.Ok(await cosmos.UpsertAsync(
                ContainerNames.Sellers, canonical, cancellationToken));
        });
        sellers.MapDelete("/{id}", async (
            string id,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            ContractValidation.Required(id, "id");
            await cosmos.DeleteAsync(ContainerNames.Sellers, id, new PartitionKey(id), cancellationToken);
            return Results.NoContent();
        });
    }

    private static void MapProducts(RouteGroupBuilder api)
    {
        RouteGroupBuilder products = api.MapGroup("/products");
        products.MapGet("/", async (
            string? sellerId,
            int? pageSize,
            string? continuation,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            int size = ContractValidation.Bounded(pageSize, 100, 1, 1000, "pageSize");
            QueryDefinition query;
            PartitionKey? partitionKey;
            if (!string.IsNullOrWhiteSpace(sellerId))
            {
                query = new QueryDefinition("SELECT * FROM c WHERE c.sellerId = @sellerId")
                    .WithParameter("@sellerId", sellerId);
                partitionKey = new PartitionKey(sellerId);
            }
            else
            {
                query = new QueryDefinition("SELECT * FROM c");
                partitionKey = null;
            }
            return Results.Ok(await cosmos.QueryPageAsync<Product>(
                ContainerNames.Products,
                query,
                partitionKey,
                size,
                continuation,
                -1,
                cancellationToken));
        });
        products.MapPost("/", async (
            Product product,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            ValidateProduct(product, requireCategory: false);
            Product created = await cosmos.CreateAsync(ContainerNames.Products, product, cancellationToken);
            return Results.Json(created, JsonDefaults.Options, statusCode: StatusCodes.Status201Created);
        });
        products.MapGet("/{sellerId}/{id}", async (
            string sellerId,
            string id,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            Product? product = await cosmos.ReadAsync<Product>(
                ContainerNames.Products,
                ContractValidation.Required(id, "id"),
                new PartitionKey(ContractValidation.Required(sellerId, "sellerId")),
                cancellationToken);
            return product is null
                ? throw ContractValidation.NotFound("Product", id)
                : Results.Ok(product);
        });
        products.MapPut("/{sellerId}/{id}", async (
            string sellerId,
            string id,
            Product product,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            Product canonical = product with
            {
                Id = ContractValidation.Required(id, "id"),
                SellerId = ContractValidation.Required(sellerId, "sellerId")
            };
            ValidateProduct(canonical, requireCategory: false);
            return Results.Ok(await cosmos.UpsertAsync(
                ContainerNames.Products, canonical, cancellationToken));
        });
        products.MapDelete("/{sellerId}/{id}", async (
            string sellerId,
            string id,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            await cosmos.DeleteAsync(
                ContainerNames.Products,
                ContractValidation.Required(id, "id"),
                new PartitionKey(ContractValidation.Required(sellerId, "sellerId")),
                cancellationToken);
            return Results.NoContent();
        });

        RouteGroupBuilder hpk = api.MapGroup("/products-hpk");
        hpk.MapGet("/{sellerId}/{categoryId}/{id}", async (
            string sellerId,
            string categoryId,
            string id,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            id = ContractValidation.Required(id, "id");
            PartitionKey pk = PartitionKeys.ForProductHpk(
                ContractValidation.Required(sellerId, "sellerId"),
                ContractValidation.Required(categoryId, "categoryId"));
            Product? product = await cosmos.ReadAsync<Product>(
                ContainerNames.ProductsHpk, id, pk, cancellationToken);
            return product is null
                ? throw ContractValidation.NotFound("Product", id)
                : Results.Ok(product);
        });
        hpk.MapPut("/{sellerId}/{categoryId}/{id}", async (
            string sellerId,
            string categoryId,
            string id,
            Product product,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            Product canonical = product with
            {
                Id = ContractValidation.Required(id, "id"),
                SellerId = ContractValidation.Required(sellerId, "sellerId"),
                CategoryId = ContractValidation.Required(categoryId, "categoryId")
            };
            ValidateProduct(canonical, requireCategory: true);
            return Results.Ok(await cosmos.UpsertAsync(
                ContainerNames.ProductsHpk, canonical, cancellationToken));
        });
    }

    private static void MapCustomers(RouteGroupBuilder api)
    {
        RouteGroupBuilder customers = api.MapGroup("/customers");
        customers.MapPost("/", async (
            Customer customer,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            ValidateCustomer(customer);
            Customer created = await cosmos.CreateAsync(ContainerNames.Customers, customer, cancellationToken);
            return Results.Json(created, JsonDefaults.Options, statusCode: StatusCodes.Status201Created);
        });
        customers.MapGet("/{id}", async (
            string id,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            id = ContractValidation.Required(id, "id");
            Customer? customer = await cosmos.ReadAsync<Customer>(
                ContainerNames.Customers, id, new PartitionKey(id), cancellationToken);
            return customer is null
                ? throw ContractValidation.NotFound("Customer", id)
                : Results.Ok(customer);
        });
    }

    private static void MapOrders(RouteGroupBuilder api)
    {
        RouteGroupBuilder orders = api.MapGroup("/orders");
        orders.MapGet("/{customerId}/{yearMonth}/{id}", async (
            string customerId,
            string yearMonth,
            string id,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            ValidateYearMonth(yearMonth);
            id = ContractValidation.Required(id, "id");
            PartitionKey pk = PartitionKeys.ForOrder(
                ContractValidation.Required(customerId, "customerId"),
                yearMonth);
            Order? order = await cosmos.ReadAsync<Order>(
                ContainerNames.Orders, id, pk, cancellationToken);
            return order is null
                ? throw ContractValidation.NotFound("Order", id)
                : Results.Ok(order);
        });
        orders.MapPut("/{customerId}/{yearMonth}/{id}", async (
            string customerId,
            string yearMonth,
            string id,
            Order order,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            ValidateYearMonth(yearMonth);
            Order canonical = order with
            {
                Id = ContractValidation.Required(id, "id"),
                CustomerId = ContractValidation.Required(customerId, "customerId"),
                YearMonth = yearMonth
            };
            ValidateOrder(canonical);
            return Results.Ok(await cosmos.UpsertAsync(
                ContainerNames.Orders, canonical, cancellationToken));
        });
    }

    private static void MapReviews(RouteGroupBuilder api)
    {
        RouteGroupBuilder reviews = api.MapGroup("/reviews");
        reviews.MapGet("/{productId}/{id}", async (
            string productId,
            string id,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            productId = ContractValidation.Required(productId, "productId");
            id = ContractValidation.Required(id, "id");
            Review? review = await cosmos.ReadAsync<Review>(
                ContainerNames.Reviews, id, new PartitionKey(productId), cancellationToken);
            return review is null
                ? throw ContractValidation.NotFound("Review", id)
                : Results.Ok(review);
        });
        reviews.MapPut("/{productId}/{id}", async (
            string productId,
            string id,
            Review review,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            Review canonical = review with
            {
                Id = ContractValidation.Required(id, "id"),
                ProductId = ContractValidation.Required(productId, "productId")
            };
            ValidateReview(canonical);
            return Results.Ok(await cosmos.UpsertAsync(
                ContainerNames.Reviews, canonical, cancellationToken));
        });
    }

    private static void MapCart(RouteGroupBuilder api)
    {
        RouteGroupBuilder carts = api.MapGroup("/cart");
        carts.MapGet("/{customerId}", async (
            string customerId,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            customerId = ContractValidation.Required(customerId, "customerId");
            Cart? cart = await cosmos.ReadAsync<Cart>(
                ContainerNames.Cart, customerId, new PartitionKey(customerId), cancellationToken);
            return cart is null
                ? throw ContractValidation.NotFound("Cart", customerId)
                : Results.Ok(cart);
        });
        carts.MapPut("/{customerId}", async (
            string customerId,
            Cart cart,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            customerId = ContractValidation.Required(customerId, "customerId");
            Cart canonical = cart with { Id = customerId, CustomerId = customerId };
            ValidateCart(canonical);
            return Results.Ok(await cosmos.UpsertAsync(
                ContainerNames.Cart, canonical, cancellationToken));
        });
    }

    private static void MapInventory(RouteGroupBuilder api)
    {
        RouteGroupBuilder inventory = api.MapGroup("/inventory");
        inventory.MapGet("/{sellerId}/{id}", async (
            string sellerId,
            string id,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            sellerId = ContractValidation.Required(sellerId, "sellerId");
            id = ContractValidation.Required(id, "id");
            Inventory? item = await cosmos.ReadAsync<Inventory>(
                ContainerNames.Inventory, id, new PartitionKey(sellerId), cancellationToken);
            return item is null
                ? throw ContractValidation.NotFound("Inventory", id)
                : Results.Ok(item);
        });
        inventory.MapPut("/{sellerId}/{id}", async (
            string sellerId,
            string id,
            Inventory inventory,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            Inventory canonical = inventory with
            {
                Id = ContractValidation.Required(id, "id"),
                SellerId = ContractValidation.Required(sellerId, "sellerId")
            };
            ValidateInventory(canonical);
            return Results.Ok(await cosmos.UpsertAsync(
                ContainerNames.Inventory, canonical, cancellationToken));
        });
    }

    private static void MapCrossCutting(RouteGroupBuilder api)
    {
        api.MapPost("/queries/run", async (
            QueryRequest request,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
            Results.Ok(await cosmos.RunQueryAsync(request, cancellationToken)));
        api.MapPost("/bulk", async (
            BulkRequest request,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
            Results.Ok(await cosmos.BulkAsync(request, cancellationToken)));
        api.MapPost("/batch", async (
            BatchRequest request,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            BatchResponse response = await cosmos.BatchAsync(request, cancellationToken);
            return Results.Json(
                response,
                JsonDefaults.Options,
                statusCode: response.Success
                    ? StatusCodes.Status200OK
                    : StatusCodes.Status409Conflict);
        });
        api.MapPost("/patch", async (
            PatchRequest request,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
            Results.Ok(await cosmos.PatchAsync(request, cancellationToken)));
        api.MapPost("/changefeed/pull", async (
            ChangeFeedPullRequest request,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
            Results.Ok(await cosmos.PullChangeFeedAsync(request, cancellationToken)));
        api.MapPost("/vector/search", async (
            VectorSearchRequest request,
            VectorCapabilityState vectorState,
            CosmosDataService cosmos,
            CancellationToken cancellationToken) =>
        {
            if (!vectorState.IsReady)
            {
                throw ContractValidation.NotImplemented("vector search");
            }
            return Results.Ok(await cosmos.VectorSearchAsync(request, cancellationToken));
        });
    }

    private static void MapUnsupportedWorkloads(RouteGroupBuilder api)
    {
        api.MapGet("/workloads", () => Results.Ok(Array.Empty<string>()));
        api.MapPost("/workloads/{id}/run", (string id) =>
            Unsupported($"workload '{id}'"));
        api.MapGet("/workloads/{id}/status/{runId}", (string id, string runId) =>
            Unsupported($"workload status '{id}/{runId}'"));
        api.MapGet("/workloads/{runId}/anomalies", (string runId) =>
            Unsupported($"workload anomalies '{runId}'"));
        api.MapGet("/workloads/{runId}/history", (string runId) =>
            Unsupported($"workload history '{runId}'"));
    }

    private static void ValidateSeller(Seller seller)
    {
        ContractValidation.Required(seller.Id, "id");
        ContractValidation.Required(seller.Name, "name");
        ContractValidation.Required(seller.Country, "country", 8);
    }

    public static int ResolveSellerLimit(int? limit, int? pageSize) =>
        ContractValidation.Bounded(limit ?? pageSize, 100, 1, 1000, limit is not null ? "limit" : "pageSize");

    private static void ValidateProduct(Product product, bool requireCategory)
    {
        ContractValidation.Required(product.Id, "id");
        ContractValidation.Required(product.SellerId, "sellerId");
        ContractValidation.Required(product.Name, "name");
        if (requireCategory)
        {
            ContractValidation.Required(product.CategoryId, "categoryId");
        }
        if (product.PriceUsd < 0)
        {
            throw ContractValidation.BadRequest("priceUsd must be non-negative.");
        }
    }

    private static void ValidateCustomer(Customer customer)
    {
        ContractValidation.Required(customer.Id, "id");
        ContractValidation.Required(customer.Name, "name");
    }

    private static void ValidateOrder(Order order)
    {
        ContractValidation.Required(order.Id, "id");
        ContractValidation.Required(order.CustomerId, "customerId");
        ValidateYearMonth(order.YearMonth);
        if (order.Items is null)
        {
            throw ContractValidation.BadRequest("items is required.");
        }
        if (order.Items.Any(item => item.Qty < 1))
        {
            throw ContractValidation.BadRequest("order item qty must be at least 1.");
        }
        if (order.Status is not null
            && order.Status is not ("pending" or "paid" or "shipped" or "delivered" or "cancelled"))
        {
            throw ContractValidation.BadRequest("status is not a recognized order status.");
        }
    }

    private static void ValidateReview(Review review)
    {
        ContractValidation.Required(review.Id, "id");
        ContractValidation.Required(review.ProductId, "productId");
        ContractValidation.Required(review.CustomerId, "customerId");
        if (review.Rating is < 1 or > 5)
        {
            throw ContractValidation.BadRequest("rating must be in [1, 5].");
        }
    }

    private static void ValidateCart(Cart cart)
    {
        ContractValidation.Required(cart.Id, "id");
        ContractValidation.Required(cart.CustomerId, "customerId");
        if (cart.Items is null)
        {
            throw ContractValidation.BadRequest("items is required.");
        }
        if (cart.Items.Any(item => item.Qty < 1))
        {
            throw ContractValidation.BadRequest("cart item qty must be at least 1.");
        }
        if (cart.Ttl is 0 or < -1)
        {
            throw ContractValidation.BadRequest("ttl must be -1, positive, or omitted.");
        }
    }

    private static void ValidateInventory(Inventory inventory)
    {
        ContractValidation.Required(inventory.Id, "id");
        ContractValidation.Required(inventory.SellerId, "sellerId");
        ContractValidation.Required(inventory.ProductId, "productId");
        if (inventory.Available < 0 || inventory.Reserved < 0)
        {
            throw ContractValidation.BadRequest("inventory counts must be non-negative.");
        }
    }

    private static void ValidateYearMonth(string yearMonth)
    {
        ContractValidation.Required(yearMonth, "yearMonth", 7);
        if (yearMonth.Length != 7
            || yearMonth[4] != '-'
            || !int.TryParse(yearMonth[..4], out _)
            || !int.TryParse(yearMonth[5..], out int month)
            || month is < 1 or > 12)
        {
            throw ContractValidation.BadRequest("yearMonth must match YYYY-MM.");
        }
    }

    private static IResult Unsupported(string feature) =>
        throw ContractValidation.NotImplemented(feature);
}
