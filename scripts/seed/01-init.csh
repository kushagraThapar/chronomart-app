mkdb ChronoMart
cd ChronoMart

mkcon Sellers      /id
mkcon Products     /sellerId
mkcon Inventory    /sellerId
mkcon Customers    /id
mkcon Reviews      /productId
mkcon Cart         /customerId
mkcon ProductsHpk  /sellerId,/categoryId,/id
mkcon Orders       /customerId,/yearMonth,/id
mkcon ChangeFeedLease /id
